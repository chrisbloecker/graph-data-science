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

import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongDoubleMap;
import com.carrotsearch.hppc.cursors.LongDoubleCursor;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.RelationshipIterator;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.paged.HugeAtomicDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArray;

import java.lang.reflect.Array;
import java.util.*;

final class MapEquationOptimizationTask implements Runnable {

    private final Graph graph;
    private final RelationshipIterator localGraph;
    private final long batchStart;
    private final long batchEnd;
    private final long color;
    private final double totalNodeWeight;
    private final Map<Integer, InfoNode> infoNodes;
    private final HugeLongArray colors;
    private final ProgressLogger progressLogger;
    private final HugeLongArray currentCommunities;
    private final HugeLongArray nextCommunities;
    private final Map<Integer, List<InfoNode>> communityUpdatesMerge;
    private final Map<Integer, List<InfoNode>> communityUpdatesRemove;
    private final HugeAtomicDoubleArray flowDistribution;

    MapEquationOptimizationTask(
        Graph graph,
        long batchStart,
        long batchEnd,
        long color,
        double totalNodeWeight,
        HugeLongArray colors,
        Map<Integer, InfoNode> infoNodes,
        HugeLongArray currentCommunities,
        HugeLongArray nextCommunities,
        Map<Integer, List<InfoNode>> communityUpdatesMerge,
        Map<Integer, List<InfoNode>> communityUpdatesRemove,
        HugeAtomicDoubleArray flowDistribution,
        ProgressLogger progressLogger
    ) {
        this.graph = graph;
        this.batchStart = batchStart;
        this.batchEnd = batchEnd;
        this.infoNodes = infoNodes;
        this.color = color;
        this.localGraph = graph.concurrentCopy();
        this.currentCommunities = currentCommunities;
        this.nextCommunities = nextCommunities;
        this.communityUpdatesMerge = communityUpdatesMerge;
        this.communityUpdatesRemove = communityUpdatesRemove;
        this.totalNodeWeight = totalNodeWeight;
        this.flowDistribution = flowDistribution;
        this.colors = colors;
        this.progressLogger = progressLogger;
    }

    @Override
    public void run() {
        // part III. for the deltas before moving a node, index level
        double part3Old = 0.0;
        // block to restrict visibility of inner
        {
            double inner = 0.0; // sum over all entry rates
            for (InfoNode infoNode : infoNodes.values())
                inner += infoNode.enterFlow();
            part3Old += inner * Math.log(inner) / Math.log(2);
        }

        for (long nodeId = batchStart; nodeId < batchEnd; nodeId++)
        {
            // Seems like this task is only trying to move nodes that have a certain colour. I think the idea is that,
            // since neighbouring nodes do not have the same colour, we can avoid some bad situations with this. Not
            // sure how much this addresses problems that come with parallelisation.
            if (colors.get(nodeId) != color) {
                continue;
            }

            long currentCommunity = currentCommunities.get(nodeId);
            Set<Long> neighbourCommunities = new TreeSet<>();
            MutableDouble nodeStrength = new MutableDouble(0.0);
            final double nodeFlow = infoNodes.get((int) nodeId).flow();

            // pull out the edges so we can actually see what's going on
            Map<Long, Double> edges = new HashMap<>();

            // figure out which communities are neighbours of this community because we will try to move this node there
            localGraph.forEachRelationship(nodeId, 1.0D, (s, t, w) -> {
                neighbourCommunities.add(currentCommunities.get(t));
                nodeStrength.add(w);
                edges.put(t, w);
                return true;
            });

            // don't check the own community
            neighbourCommunities.remove(currentCommunity);

            // where should we move the current node?
            long nextCommunity = currentCommunity;
            double maxGain = 0.0;
            // deltas for the best moves
            InfoNode currentDelta = new InfoNode();
            InfoNode nextDelta = new InfoNode();

            // calculate deltas for all neighbour communities
            // we assume an undirected network, or rather: if there is a link (s, t, w), then there is also (t, s, w)!
            for (long candidateCommunity : neighbourCommunities)
            {
                double currentGain = 0.0;

                InfoNode deltaCurrentCommunity = new InfoNode();
                deltaCurrentCommunity.addNode((int) nodeId);
                deltaCurrentCommunity.addFlow(-nodeFlow);

                InfoNode deltaCandidateCommunity = new InfoNode();
                deltaCandidateCommunity.addNode((int) nodeId);
                deltaCandidateCommunity.addFlow(nodeFlow);

                //localGraph.forEachRelationship(nodeId, 1.0D, (s, t, w) -> {
                for (Long t : edges.keySet()) {
                    double w = nodeFlow * edges.get(t) / nodeStrength.doubleValue();
                    // the link is within currentCommunity [case (a)]
                    //  -> it will be between currentCommunity and candidateCommunity after moving
                    if (currentCommunities.get(t) == currentCommunity) {
                        deltaCurrentCommunity.addEnterFlow(w);
                        deltaCurrentCommunity.addExitFlow(w);
                        deltaCandidateCommunity.addEnterFlow(w);
                        deltaCandidateCommunity.addExitFlow(w);
                    }
                    // the link is between currentCommunity and candidateCommunity [case (b)]
                    //  -> it will be within candidateCommunity after moving
                    else if (currentCommunities.get(t) == candidateCommunity) {
                        deltaCurrentCommunity.addEnterFlow(-w);
                        deltaCurrentCommunity.addExitFlow(-w);
                        deltaCandidateCommunity.addEnterFlow(-w);
                        deltaCandidateCommunity.addExitFlow(-w);
                    }
                    // the link is between currentCommunity and some other community that is not candidateCommunity
                    //  -> it will be between candidateCommunity and that other community after moving
                    else {
                        deltaCurrentCommunity.addEnterFlow(-w);
                        deltaCurrentCommunity.addExitFlow(-w);
                        deltaCandidateCommunity.addEnterFlow(w);
                        deltaCandidateCommunity.addExitFlow(w);
                    }
                }
                //    return true;
                //});

                InfoNode currentCommunityOld   = infoNodes.get((int) currentCommunity);
                InfoNode candidateCommunityOld = infoNodes.get((int) candidateCommunity);
                InfoNode currentCommunityNew   = currentCommunityOld.remove(deltaCurrentCommunity);
                InfoNode candidateCommunityNew = candidateCommunityOld.merge(deltaCandidateCommunity);

                // remove codelength influences from the old structure
                // I.
                currentGain += currentCommunityOld.enterLogEnter()
                            +  candidateCommunityOld.enterLogEnter()
                            -  currentCommunityNew.enterLogEnter()
                            -  candidateCommunityNew.enterLogEnter();

                // II.
                currentGain += currentCommunityOld.exitLogExit()
                            +  candidateCommunityOld.exitLogExit()
                            -  currentCommunityNew.exitLogExit()
                            -  candidateCommunityNew.exitLogExit();

                // III.
                double part3New = 0.0;
                {
                    double inner = 0.0;
                    for (int community = 0; community < infoNodes.size(); ++community)
                        if (community != currentCommunity && community != candidateCommunity)
                            inner += infoNodes.get(community).enterFlow();
                    inner += currentCommunityNew.enterFlow()
                          +  candidateCommunityNew.enterFlow();
                    for (int community = 0; community < infoNodes.size(); ++community)
                        if (community != currentCommunity && community != candidateCommunity)
                            part3New += infoNodes.get(community).enterFlow() * Math.log(inner) / Math.log(2);
                    part3New += currentCommunityNew.enterFlow() * Math.log(inner) / Math.log(2)
                             +  candidateCommunityNew.enterFlow() * Math.log(inner) / Math.log(2);
                }
                currentGain += part3Old
                            -  part3New;

                // IV.
                currentGain += currentCommunityOld.flowLogFlow()
                            +  candidateCommunityOld.flowLogFlow()
                            -  currentCommunityNew.flowLogFlow()
                            -  candidateCommunityNew.flowLogFlow();

                if ((currentGain < maxGain) || (currentGain == maxGain && currentGain != 0.0 && nextCommunity > candidateCommunity)) {
                    maxGain = currentGain;
                    nextCommunity = candidateCommunity;
                    currentDelta = deltaCurrentCommunity;
                    nextDelta = deltaCandidateCommunity;
                }
            }

            if (nextCommunity != currentCommunity)
            {
                nextCommunities.set(nodeId, nextCommunity);
                communityUpdatesMerge.get((int) nextCommunity).add(nextDelta);
                communityUpdatesRemove.get((int) currentCommunity).add(currentDelta);
            }
            progressLogger.logProgress(graph.degree(nodeId));
        }

    }
}
