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
import org.neo4j.graphalgo.core.utils.AtomicDoubleArray;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.*;
import org.neo4j.graphalgo.core.utils.partition.Partition;
import org.neo4j.graphalgo.core.utils.partition.PartitionUtils;
import org.neo4j.graphalgo.pagerank.ImmutablePageRankStreamConfig;
import org.neo4j.graphalgo.pagerank.PageRank;
import org.neo4j.graphalgo.pagerank.PageRankFactory;
import org.neo4j.graphalgo.pagerank.PageRankStreamConfig;

import javax.ws.rs.core.Link;
import java.util.*;
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
    // a mapping from communities to InfoNodes
    private Map<Integer, InfoNode> infoNodes;
    private HugeLongArray colors;
    private HugeLongArray currentCommunities;
    private HugeLongArray nextCommunities;
    private HugeLongArray reverseSeedCommunityMapping;
    private Map<Integer, List<InfoNode>> communityUpdatesMerge;
    private Map<Integer, List<InfoNode>> communityUpdatesRemove;
    private HugeDoubleArray nodeStrengths;
    private HugeDoubleArray flowDistribution;
    private Map<Long, Map<Long, Double>> predecessors;
    private Map<Long, Map<Long, Double>> successors;

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
        computeFlowDistribution();
        computeColoring();
        initSeeding();
        init();
        this.codelength = calculateCodelength();
        progressLogger.logMessage(":: Initialization :: Finished");


        for (iterationCounter = 0; iterationCounter < maxIterations; iterationCounter++) {
            progressLogger.logMessage(formatWithLocale(":: Iteration %d :: Start", iterationCounter + 1));

            boolean hasConverged;

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

    private void computeFlowDistribution() {
        PageRankStreamConfig prConfig = ImmutablePageRankStreamConfig
            .builder()
            .concurrency(concurrency)
            .maxIterations(100)
            .dampingFactor(0.85)
            .build();

        PageRank pr = new PageRankFactory<>()
            .build(graph, prConfig, tracker, progressLogger.getLog(), progressLogger.eventTracker())
            .withTerminationFlag(terminationFlag);

        HugeDoubleArray ar = pr.compute().result().array();
        double sum = ar.stream().sum();
        this.flowDistribution = HugeDoubleArray.newArray(ar.size(), tracker);
        for (long i = 0; i < ar.size(); ++i)
            this.flowDistribution.set(i, ar.get(i) / sum);

        getProgressLogger().logMessage("Flow sum is " + Double.toString(sum));
        getProgressLogger().logMessage("Flow distribution " + this.flowDistribution.toString());
    }

    private void computeColoring() {
        K1ColoringStreamConfig k1Config = ImmutableK1ColoringStreamConfig
            .builder()
            .concurrency(concurrency)
            .maxIterations(5)
            .batchSize((int) batchSize)
            .build();

        K1Coloring coloring = new K1ColoringFactory<>()
            .build(graph, k1Config, tracker, progressLogger.getLog(), progressLogger.eventTracker())
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
        this.communityUpdatesMerge = new HashMap<>();
        this.communityUpdatesRemove = new HashMap<>();
        this.infoNodes     = new HashMap<>();
        this.nodeStrengths = HugeDoubleArray.newArray(graph.nodeCount(), tracker);
        this.predecessors  = new HashMap<>();
        this.successors    = new HashMap<>();

        graph.forEachNode(nodeId -> {
            this.predecessors.put(nodeId, new HashMap<>());
            this.successors.put(nodeId, new HashMap<>());
            return true;
        });

        graph.forEachNode(nodeId -> {
            graph.forEachRelationship(nodeId, 1.0D, (s, t, w) -> {
                this.nodeStrengths.addTo(nodeId, w);
                this.predecessors.get(t).put(s, w);
                this.successors.get(s).put(t, w);
                return true;
            });
            return true;
        });

        var initTasks = PartitionUtils.rangePartition(concurrency, nodeCount)
            .stream()
            .map(partition -> new InitTask(
                graph.concurrentCopy(),
                currentCommunities,
                seedProperty != null,
                partition,
                this.flowDistribution,
                this.infoNodes
            ))
            .collect(Collectors.toList());

        ParallelUtil.run(initTasks, executor);

        // we have to do this after the init tasks because start communities are set there.
        for (int ix = 0; ix < currentCommunities.size(); ++ix)
        {
            if (!communityUpdatesMerge.containsKey((int) currentCommunities.get(ix)))
                this.communityUpdatesMerge.put((int) currentCommunities.get(ix), new LinkedList<>());
            if (!communityUpdatesRemove.containsKey((int) currentCommunities.get(ix)))
                this.communityUpdatesRemove.put((int) currentCommunities.get(ix), new LinkedList<>());
        }

        currentCommunities.copyTo(nextCommunities, nodeCount);
    }

    private static final class InitTask implements Runnable {

        private final RelationshipIterator relationshipIterator;

        private final HugeLongArray currentCommunities;

        private final boolean isSeeded;

        private final Partition partition;

        private final HugeDoubleArray flowDistribution;

        private final Map<Integer, InfoNode> infoNodes;

        private InitTask(
            RelationshipIterator relationshipIterator,
            HugeLongArray currentCommunities,
            boolean isSeeded,
            Partition partition,
            HugeDoubleArray flowDistribution,
            Map<Integer, InfoNode> infoNodes
        ) {
            this.relationshipIterator = relationshipIterator;
            this.currentCommunities = currentCommunities;
            this.isSeeded = isSeeded;
            this.partition = partition;
            this.flowDistribution = flowDistribution;
            this.infoNodes = infoNodes;
        }

        @Override
        public void run() {
            for (long nodeId = partition.startNode(); nodeId < partition.startNode() + partition.nodeCount(); nodeId++) {
                if (!isSeeded) {
                    currentCommunities.set(nodeId, nodeId);
                }

                InfoNode infoNode = new InfoNode();
                infoNode.addNode((int) nodeId);

                final double nodeFlow = flowDistribution.get(nodeId);

                // summing over outgoing link weights
                MutableDouble cumulativeWeight = new MutableDouble(0.0);

                relationshipIterator.forEachRelationship(nodeId, 1.0, (s, t, w) -> {
                    cumulativeWeight.add(w);
                    return true;
                });

                // technically, in the following, we should get the respective InfoNode from infoNodes and update its
                // properties. but since we assume an undirected network, we can do all calculations locally, focused
                // on only one node.
                // plus, we would have to secure this against race conditions...
                //
                // so we should:
                // infoNodes.get((int) s).addExitFlow(f)
                // infoNodes.get((int) t).addEnterFlow(f)
                // infoNodes.get((int) t).addFlow(f)
                relationshipIterator.forEachRelationship(nodeId, 1.0, (s, t, w) -> {
                    final double f = nodeFlow * w / cumulativeWeight.doubleValue();
                    infoNode.addExitFlow(f);
                    infoNode.addEnterFlow(f);
                    infoNode.addFlow(f);
                    return true;
                });

                infoNodes.put((int) nodeId, infoNode);
            }
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

        // apply community updates
        for (int community = 0; community < communityUpdatesMerge.size(); ++community)
        {
            for (InfoNode mergeUpdate : communityUpdatesMerge.get(community))
            {
                InfoNode newInfo = infoNodes.get(community).merge(mergeUpdate);
                infoNodes.put(community, newInfo);
            }
            communityUpdatesMerge.get(community).clear();
        }

        for (int community = 0; community < communityUpdatesRemove.size(); ++community)
        {
            for (InfoNode removeUpdate : communityUpdatesRemove.get(community))
            {
                InfoNode newInfo = infoNodes.get(community).remove(removeUpdate);
                infoNodes.put(community, newInfo);
            }
            communityUpdatesRemove.get(community).clear();
        }
    }

    private Collection<MapEquationOptimizationTask> createMapEquationOptimizationTasks(long currentColor) {
        final Collection<MapEquationOptimizationTask> tasks = new ArrayList<>(concurrency);
        for (long i = 0L; i < this.nodeCount; i += batchSize) {
            tasks.add(
                new MapEquationOptimizationTask(
                    graph,
                    predecessors,
                    successors,
                    i,
                    Math.min(i + batchSize, nodeCount),
                    currentColor,
                    totalNodeWeight,
                    colors,
                    nodeStrengths,
                    infoNodes,
                    currentCommunities,
                    nextCommunities,
                    communityUpdatesMerge,
                    communityUpdatesRemove,
                    flowDistribution,
                    getProgressLogger()
                )
            );
        }
        return tasks;
    }

    private boolean updateCodelength() {
        double oldCodelength = this.codelength;
        this.codelength = calculateCodelength();

        return this.codelength < oldCodelength
            && Math.abs(this.codelength - oldCodelength) > tolerance;
    }

    private double calculateCodelength(){
        double res = 0.0;

        for (InfoNode infoNode: infoNodes.values())
            res += infoNode.flowLogFlow()   // IV. "flow_log_flow"
                -  infoNode.enterLogEnter() //  I. "enter_log_enter"
                -  infoNode.exitLogExit();  // II. "exit_log_exit"

        // III. "enterFlow_log_enterFlow"
        double inFlowSum = 0.0;
        for (InfoNode infoNode : infoNodes.values())
            inFlowSum += infoNode.enterFlow();
        res += inFlowSum * Math.log(inFlowSum) / Math.log(2);

        // V. "nodeFlow_log_nodeFlow"
        double f;
        for (int ix = 0; ix < flowDistribution.size(); ++ix)
        {
            f = flowDistribution.get(ix);
            res -= f * Math.log(f) / Math.log(2);
        }

        return res;
    }

    @Override
    public MapEquationOptimization me() {
        return this;
    }

    @Override
    public void release() {
        this.nextCommunities.release();
        this.colors.release();
        this.nodeStrengths.release();
        this.colorsUsed = null;
        this.flowDistribution.release();
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
