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
package org.neo4j.graphalgo.infomap;

import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.NodeMapping;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.api.RelationshipIterator;
import org.neo4j.graphalgo.api.nodeproperties.LongNodeProperties;
import org.neo4j.graphalgo.beta.mapequation.ImmutableMapEquationOptimizationStreamConfig;
import org.neo4j.graphalgo.beta.mapequation.MapEquationOptimization;
import org.neo4j.graphalgo.beta.mapequation.MapEquationOptimizationFactory;
import org.neo4j.graphalgo.beta.mapequation.MapEquationOptimizationStreamConfig;
import org.neo4j.graphalgo.core.Aggregation;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.loading.IdMap;
import org.neo4j.graphalgo.core.loading.construction.GraphFactory;
import org.neo4j.graphalgo.core.loading.construction.RelationshipsBuilder;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArray;
import org.neo4j.graphalgo.core.utils.partition.Partition;
import org.neo4j.graphalgo.core.utils.partition.PartitionUtils;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.neo4j.graphalgo.core.concurrency.ParallelUtil.DEFAULT_BATCH_SIZE;
import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

public final class Infomap extends Algorithm<Infomap, Infomap> {

    private final Graph rootGraph;
    private final InfomapBaseConfig config;
    private final NodeProperties seedingValues;
    private final ExecutorService executorService;
    private final AllocationTracker tracker;
    // results
    private HugeLongArray[] dendrograms;
    private double[] codelengths;
    private int ranLevels;

    public Infomap(
        Graph graph,
        InfomapBaseConfig config,
        ExecutorService executorService,
        ProgressLogger progressLogger,
        AllocationTracker tracker
    ) {
        this.config = config;
        this.rootGraph = graph;
        this.seedingValues = Optional.ofNullable(config.seedProperty()).map(graph::nodeProperties).orElse(null);
        this.executorService = executorService;
        this.tracker = tracker;
        this.dendrograms = new HugeLongArray[config.maxLevels()];
        this.codelengths = new double[config.maxLevels()];
        this.progressLogger = progressLogger;
    }

    @Override
    public Infomap compute() {
        getProgressLogger().logMessage(":: Start");

        Graph workingGraph = rootGraph;
        NodeProperties nextSeedingValues = seedingValues;

        long oldNodeCount = rootGraph.nodeCount();
        for (ranLevels = 0; ranLevels < config.maxLevels(); ranLevels++) {
            getProgressLogger().logMessage(formatWithLocale("Level %d :: Start", ranLevels + 1));

            assertRunning();

            MapEquationOptimization mapEquationOptimization = runMapEquationOptimization(
                workingGraph,
                nextSeedingValues
            );
            mapEquationOptimization.release();

            codelengths[ranLevels] = mapEquationOptimization.getCodelength();
            dendrograms[ranLevels] = HugeLongArray.newArray(rootGraph.nodeCount(), tracker);
            long maxCommunityId = buildDendrogram(workingGraph, ranLevels, mapEquationOptimization);

            workingGraph = summarizeGraph(workingGraph, mapEquationOptimization, maxCommunityId);
            nextSeedingValues = new OriginalIdNodeProperties(workingGraph);

            getProgressLogger().logMessage(formatWithLocale("Level %d :: Finished", ranLevels + 1));


            if (workingGraph.nodeCount() == oldNodeCount
                || workingGraph.nodeCount() == 1
                || hasConverged()
            ) {
                resizeResultArrays();
                getProgressLogger().logMessage(":: Finished");
                break;
            }
            oldNodeCount = workingGraph.nodeCount();
        }

        return this;
    }

    private void resizeResultArrays() {
        int numLevels = levels();
        HugeLongArray[] resizedDendrogram = new HugeLongArray[numLevels];
        double[] resizedCodelengths = new double[numLevels];
        if (numLevels < this.dendrograms.length) {
            System.arraycopy(this.dendrograms, 0, resizedDendrogram, 0, numLevels);
            System.arraycopy(this.codelengths, 0, resizedCodelengths, 0, numLevels);
        }
        this.dendrograms = resizedDendrogram;
        this.codelengths = resizedCodelengths;
    }

    private long buildDendrogram(
        Graph workingGraph,
        int level,
        MapEquationOptimization mapEquationOptimization
    ) {
        AtomicLong maxCommunityId = new AtomicLong(0L);
        ParallelUtil.parallelForEachNode(rootGraph, config.concurrency(), (nodeId) -> {
            long prevId = level == 0
                ? nodeId
                : workingGraph.toMappedNodeId(dendrograms[level - 1].get(nodeId));

            long communityId = mapEquationOptimization.getCommunityId(prevId);

            boolean updatedMaxCurrentId;
            do {
                var currentMaxId = maxCommunityId.get();
                if (communityId > currentMaxId) {
                    updatedMaxCurrentId = maxCommunityId.compareAndSet(currentMaxId, communityId);
                } else {
                    updatedMaxCurrentId = true;
                }
            } while (!updatedMaxCurrentId);

            dendrograms[level].set(nodeId, communityId);
        });

        return maxCommunityId.get();
    }

    private MapEquationOptimization runMapEquationOptimization(Graph infomapGraph, NodeProperties seed) {
        MapEquationOptimizationStreamConfig mapEquationOptimizationConfig = ImmutableMapEquationOptimizationStreamConfig
            .builder()
            .maxIterations(config.maxIterations())
            .tolerance(config.tolerance())
            .concurrency(config.concurrency())
            .batchSize(DEFAULT_BATCH_SIZE)
            .build();

        MapEquationOptimization mapEquationOptimization = new MapEquationOptimizationFactory<>()
            .build(
                infomapGraph,
                mapEquationOptimizationConfig,
                seed,
                tracker,
                progressLogger.getLog(),
                progressLogger.eventTracker()
            ).withTerminationFlag(terminationFlag);

        mapEquationOptimization.compute();

        return mapEquationOptimization;
    }

    private Graph summarizeGraph(
        Graph workingGraph,
        MapEquationOptimization mapEquationOptimization,
        long maxCommunityId
    ) {
        var nodesBuilder = GraphFactory.initNodesBuilder()
            .maxOriginalId(maxCommunityId)
            .concurrency(config.concurrency())
            .tracker(tracker)
            .build();

        assertRunning();

        workingGraph.forEachNode((nodeId) -> {
            nodesBuilder.addNode(mapEquationOptimization.getCommunityId(nodeId));
            return true;
        });

        assertRunning();

        Orientation orientation = rootGraph.isUndirected() ? Orientation.UNDIRECTED : Orientation.NATURAL;
        NodeMapping idMap = nodesBuilder.build();
        RelationshipsBuilder relationshipsBuilder = GraphFactory.initRelationshipsBuilder()
            .nodes(idMap)
            .orientation(orientation)
            .loadRelationshipProperty(true)
            .aggregation(Aggregation.SUM)
            .preAggregate(true)
            .executorService(executorService)
            .tracker(tracker)
            .build();

        var relationshipCreators = PartitionUtils
            .rangePartition(config.concurrency(), workingGraph.nodeCount())
            .stream()
            .map(partition ->
                new RelationshipCreator(
                    relationshipsBuilder,
                    mapEquationOptimization,
                    workingGraph.concurrentCopy(),
                    partition
                ))
            .collect(Collectors.toList());

        ParallelUtil.run(relationshipCreators, executorService);

        return GraphFactory.create(idMap, relationshipsBuilder.build(), tracker);
    }

    private boolean hasConverged() {
        if (ranLevels == 0) {
            return false;
        }

        double previousCodelength = codelengths[ranLevels - 1];
        double currentCodelength = codelengths[ranLevels];
        return !(currentCodelength > previousCodelength && Math.abs(currentCodelength - previousCodelength) > config.tolerance());
    }

    public InfomapBaseConfig config() {
        return this.config;
    }

    public HugeLongArray[] dendrograms() {
        return this.dendrograms;
    }

    public HugeLongArray finalDendrogram() {
        return this.dendrograms[levels() - 1];
    }

    public long getCommunity(long nodeId) {
        return dendrograms[levels() - 1].get(nodeId);
    }

    public long[] getCommunities(long nodeId) {
        long[] communities = new long[dendrograms.length];

        for (int i = 0; i < dendrograms.length; i++) {
            communities[i] = dendrograms[i].get(nodeId);
        }

        return communities;
    }

    public int levels() {
        return this.ranLevels == 0 ? 1 : this.ranLevels;
    }

    public double[] codelengths() {
        return this.codelengths;
    }

    @Override
    public void release() {
        this.rootGraph.releaseTopology();
    }

    @Override
    public Infomap me() {
        return this;
    }

    static class OriginalIdNodeProperties implements LongNodeProperties {
        private final Graph graph;

        public OriginalIdNodeProperties(Graph graph) {
            this.graph = graph;
        }

        @Override
        public long longValue(long nodeId) {
            return graph.toOriginalNodeId(nodeId);
        }

        @Override
        public Value value(long nodeId) {
            return Values.longValue(longValue(nodeId));
        }

        @Override
        public OptionalLong getMaxLongPropertyValue() {
            return OptionalLong.empty();
        }
    }

    static final class RelationshipCreator implements Runnable {

        private final RelationshipsBuilder relationshipsBuilder;

        private final MapEquationOptimization mapEquationOptimization;

        private final RelationshipIterator relationshipIterator;

        private final Partition partition;


        private RelationshipCreator(
            RelationshipsBuilder relationshipsBuilder,
            MapEquationOptimization mapEquationOptimization,
            RelationshipIterator relationshipIterator,
            Partition partition
        ) {
            this.relationshipsBuilder = relationshipsBuilder;
            this.mapEquationOptimization = mapEquationOptimization;
            this.relationshipIterator = relationshipIterator;
            this.partition = partition;
        }

        @Override
        public void run() {
            long endNodeId = partition.startNode() + partition.nodeCount();
            for (long nodeId = partition.startNode(); nodeId < endNodeId; nodeId++) {
                long communityId = mapEquationOptimization.getCommunityId(nodeId);
                relationshipIterator.forEachRelationship(nodeId, 1.0, (source, target, property) -> {
                    relationshipsBuilder.add(communityId, mapEquationOptimization.getCommunityId(target), property);
                    return true;
                });
            }
        }
    }
}
