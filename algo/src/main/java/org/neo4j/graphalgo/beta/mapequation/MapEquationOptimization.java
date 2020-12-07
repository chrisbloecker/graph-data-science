/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.beta.mapequation;

import com.carrotsearch.hppc.BitSet;
import com.carrotsearch.hppc.cursors.LongLongCursor;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.jetbrains.annotations.Nullable;
import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.api.RelationshipIterator;
import org.neo4j.graphalgo.api.nodeproperties.LongNodeProperties;
import org.neo4j.graphalgo.beta.k1coloring.ImmutableK1ColoringStreamConfig;
import org.neo4j.graphalgo.beta.k1coloring.K1Coloring;
import org.neo4j.graphalgo.beta.k1coloring.K1ColoringFactory;
import org.neo4j.graphalgo.beta.k1coloring.K1ColoringStreamConfig;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeAtomicDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArray;
import org.neo4j.graphalgo.core.utils.paged.HugeLongLongMap;
import org.neo4j.graphalgo.core.utils.partition.Partition;
import org.neo4j.graphalgo.core.utils.partition.PartitionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

/**
 * Implementation of parallel map equation optimisation.
 *
 * Implementation of parallel modularity optimization based on:
 *
 * Lu, Hao, Mahantesh Halappanavar, and Ananth Kalyanaraman.
 * "Parallel heuristics for scalable community detection."
 * Parallel Computing 47 (2015): 19-37.
 * https://arxiv.org/pdf/1410.1237.pdf
 */
public final class MapEquationOptimization extends Algorithm<MapEquationOptimization, MapEquationOptimization> {

    private final int concurrency;
    private final int maxIterations;
    private final long nodeCount;
    private final long batchSize;
    private final double tolerance;
    private final Graph graph;
    private final NodeProperties seedProperty;
    private final ExecutorService executor;
    private final AllocationTracker tracker;

    private int iterationCounter;
    private boolean didConverge = false;
    private double totalNodeWeight = 0.0;
    private double codelength = -1.0;
    private BitSet colorsUsed;
    private HugeLongArray colors;
    private HugeLongArray currentCommunities;
    private HugeLongArray nextCommunities;
    private HugeLongArray reverseSeedCommunityMapping;
    private HugeDoubleArray cumulativeNodeWeights;
    private HugeDoubleArray nodeCommunityInfluences;
    private HugeAtomicDoubleArray communityWeights;
    private HugeAtomicDoubleArray communityWeightUpdates;

    public MapEquationOptimization(
        final Graph graph,
        int maxIterations,
        double tolerance,
        @Nullable NodeProperties seedProperty,
        int concurrency,
        int minBatchSize,
        ExecutorService executor,
        ProgressLogger progressLogger,
        AllocationTracker tracker
    ) {
        this.graph = graph;
        this.nodeCount = graph.nodeCount();
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
        this.seedProperty = seedProperty;
        this.executor = executor;
        this.concurrency = concurrency;
        this.progressLogger = progressLogger;
        this.tracker = tracker;
        this.batchSize = ParallelUtil.adjustedBatchSize(
            nodeCount,
            concurrency,
            minBatchSize,
            Integer.MAX_VALUE
        );

        if (maxIterations < 1) {
            throw new IllegalArgumentException(formatWithLocale(
                "Need to run at least one iteration, but got %d",
                maxIterations
            ));
        }
    }

    @Override
    public MapEquationOptimization compute() {
        progressLogger.logMessage(":: Start");


        progressLogger.logMessage(":: Initialization :: Start");
        computeColoring();
        initSeeding();
        init();
        progressLogger.logMessage(":: Initialization :: Finished");


        for (iterationCounter = 0; iterationCounter < maxIterations; iterationCounter++) {
            progressLogger.logMessage(formatWithLocale(":: Iteration %d :: Start", iterationCounter + 1));

            boolean hasConverged;

            nodeCommunityInfluences.fill(0.0);

            long currentColor = colorsUsed.nextSetBit(0);
            while (currentColor != -1) {
                assertRunning();
                optimizeForColor(currentColor);
                currentColor = colorsUsed.nextSetBit(currentColor + 1);
            }

            hasConverged = !updateCodelength();

            progressLogger.logMessage(formatWithLocale(":: Iteration %d :: Finished", iterationCounter + 1));

            if (hasConverged) {
                this.didConverge = true;
                iterationCounter++;
                break;
            }

            progressLogger.reset(graph.relationshipCount());
        }

        progressLogger.logMessage(":: Finished");
        return this;
    }

    private void computeColoring() {
        K1ColoringStreamConfig k1Config = ImmutableK1ColoringStreamConfig
            .builder()
            .concurrency(concurrency)
            .maxIterations(5)
            .batchSize((int) batchSize)
            .build();

        K1Coloring coloring = new K1ColoringFactory<>()
            .build(graph, k1Config, tracker, progressLogger.getLog())
            .withTerminationFlag(terminationFlag);

        this.colors = coloring.compute();
        this.colorsUsed = coloring.usedColors();
    }

    private void initSeeding() {
        this.currentCommunities = HugeLongArray.newArray(nodeCount, tracker);

        if (seedProperty == null) {
            return;
        }

        long maxSeedCommunity = seedProperty.getMaxLongPropertyValue().orElse(0L);

        HugeLongLongMap communityMapping = new HugeLongLongMap(nodeCount, tracker);
        long nextAvailableInternalCommunityId = -1;

        for (long nodeId = 0; nodeId < nodeCount; nodeId++) {
            long seedCommunity = seedProperty.longValue(nodeId);
            if (seedCommunity < 0) {
                seedCommunity = -1;
            }

            seedCommunity = seedCommunity >= 0 ? seedCommunity : graph.toOriginalNodeId(nodeId) + maxSeedCommunity;
            if (communityMapping.getOrDefault(seedCommunity, -1) < 0) {
                communityMapping.addTo(seedCommunity, ++nextAvailableInternalCommunityId);
            }

            currentCommunities.set(nodeId, communityMapping.getOrDefault(seedCommunity, -1));
        }

        this.reverseSeedCommunityMapping = HugeLongArray.newArray(communityMapping.size(), tracker);

        for (LongLongCursor entry : communityMapping) {
            reverseSeedCommunityMapping.set(entry.value, entry.key);
        }
    }

    private void init() {
        this.nextCommunities = HugeLongArray.newArray(nodeCount, tracker);
        this.cumulativeNodeWeights = HugeDoubleArray.newArray(nodeCount, tracker);
        this.nodeCommunityInfluences = HugeDoubleArray.newArray(nodeCount, tracker);
        this.communityWeights = HugeAtomicDoubleArray.newArray(nodeCount, tracker);
        this.communityWeightUpdates = HugeAtomicDoubleArray.newArray(nodeCount, tracker);

        var initTasks = PartitionUtils.rangePartition(concurrency, nodeCount)
            .stream()
            .map(partition -> new InitTask(
                graph.concurrentCopy(),
                currentCommunities,
                communityWeights,
                cumulativeNodeWeights,
                seedProperty != null,
                partition
            ))
            .collect(Collectors.toList());

        ParallelUtil.run(initTasks, executor);

        var doubleTotalNodeWeight = initTasks.stream().mapToDouble(InitTask::localSum).sum();

        totalNodeWeight = doubleTotalNodeWeight / 2.0;
        currentCommunities.copyTo(nextCommunities, nodeCount);
    }

    private static final class InitTask implements Runnable {

        private final RelationshipIterator relationshipIterator;

        private final HugeLongArray currentCommunities;

        private final HugeAtomicDoubleArray communityWeights;

        private final HugeDoubleArray cumulativeNodeWeights;

        private final boolean isSeeded;

        private final Partition partition;

        private double localSum;

        private InitTask(
            RelationshipIterator relationshipIterator,
            HugeLongArray currentCommunities,
            HugeAtomicDoubleArray communityWeights,
            HugeDoubleArray cumulativeNodeWeights,
            boolean isSeeded,
            Partition partition
        ) {
            this.relationshipIterator = relationshipIterator;
            this.currentCommunities = currentCommunities;
            this.communityWeights = communityWeights;
            this.cumulativeNodeWeights = cumulativeNodeWeights;
            this.isSeeded = isSeeded;
            this.partition = partition;
            this.localSum = 0.0D;
        }

        @Override
        public void run() {
            var cumulativeWeight = new MutableDouble();

            for (long nodeId = partition.startNode(); nodeId < partition.startNode() + partition.nodeCount(); nodeId++) {
                if (!isSeeded) {
                    currentCommunities.set(nodeId, nodeId);
                }

                cumulativeWeight.setValue(0.0D);

                relationshipIterator.forEachRelationship(nodeId, 1.0, (s, t, w) -> {
                    cumulativeWeight.add(w);
                    return true;
                });

                communityWeights.update(
                    currentCommunities.get(nodeId),
                    acc -> acc + cumulativeWeight.doubleValue()
                );

                cumulativeNodeWeights.set(nodeId, cumulativeWeight.doubleValue());

                localSum += cumulativeWeight.doubleValue();
            }
        }

        double localSum() {
            return localSum;
        }
    }

    private void optimizeForColor(long currentColor) {
        // run optimization tasks for every node
        ParallelUtil.runWithConcurrency(
            concurrency,
            createMapEquationOptimizationTasks(currentColor),
            executor
        );

        // swap old and new communities
        nextCommunities.copyTo(currentCommunities, nodeCount);

        // apply communityWeight updates to communityWeights
        ParallelUtil.parallelStreamConsume(
            LongStream.range(0, nodeCount),
            concurrency,
            stream -> stream.forEach(nodeId -> {
                final double update = communityWeightUpdates.get(nodeId);
                communityWeights.update(nodeId, w -> w + update);
            })
        );

        // reset communityWeightUpdates
        communityWeightUpdates = HugeAtomicDoubleArray.newArray(nodeCount, tracker);
    }

    private Collection<MapEquationOptimizationTask> createMapEquationOptimizationTasks(long currentColor) {
        final Collection<MapEquationOptimizationTask> tasks = new ArrayList<>(concurrency);
        for (long i = 0L; i < this.nodeCount; i += batchSize) {
            tasks.add(
                new MapEquationOptimizationTask(
                    graph,
                    i,
                    Math.min(i + batchSize, nodeCount),
                    currentColor,
                    totalNodeWeight,
                    colors,
                    currentCommunities,
                    nextCommunities,
                    cumulativeNodeWeights,
                    nodeCommunityInfluences,
                    communityWeights,
                    communityWeightUpdates,
                    getProgressLogger()
                )
            );
        }
        return tasks;
    }

    private boolean updateCodelength() {
        double oldCodelength = this.codelength;
        this.codelength = calculateCodelength();

        return this.codelength > oldCodelength && Math.abs(this.codelength - oldCodelength) > tolerance;
    }

    // ToDo[chris] this is what needs to change
    private double calculateCodelength() {
        double ex = ParallelUtil.parallelStream(
            LongStream.range(0, nodeCount),
            concurrency,
            nodeStream ->
                nodeStream
                    .mapToDouble(nodeCommunityInfluences::get)
                    .reduce(Double::sum)
                    .orElseThrow(() -> new RuntimeException("Error while comptuing codelength"))
        );

        double ax = ParallelUtil.parallelStream(
            LongStream.range(0, nodeCount),
            concurrency,
            nodeStream ->
                nodeStream
                    .mapToDouble(nodeId -> Math.pow(communityWeights.get(nodeId), 2.0))
                    .reduce(Double::sum)
                    .orElseThrow(() -> new RuntimeException("Error while comptuing codelength"))
        );

        return (ex / (2 * totalNodeWeight)) - (ax / (Math.pow(2 * totalNodeWeight, 2)));
    }

    @Override
    public MapEquationOptimization me() {
        return this;
    }

    @Override
    public void release() {
        this.nextCommunities.release();
        this.communityWeights.release();
        this.communityWeightUpdates.release();
        this.cumulativeNodeWeights.release();
        this.nodeCommunityInfluences.release();
        this.colors.release();
        this.colorsUsed = null;
    }

    public long getCommunityId(long nodeId) {
        if (seedProperty == null || reverseSeedCommunityMapping == null) {
            return currentCommunities.get(nodeId);
        }
        return reverseSeedCommunityMapping.get(currentCommunities.get(nodeId));
    }

    public int getIterations() {
        return this.iterationCounter;
    }

    public double getCodelength() {
        return this.codelength;
    }

    public boolean didConverge() {
        return this.didConverge;
    }

    public double getTolerance() {
        return tolerance;
    }

    public LongNodeProperties asNodeProperties() {
        return new LongNodeProperties() {
            @Override
            public long longValue(long nodeId) {
                return getCommunityId(nodeId);
            }

            @Override
            public long size() {
                return currentCommunities.size();
            }
        };
    }
}
