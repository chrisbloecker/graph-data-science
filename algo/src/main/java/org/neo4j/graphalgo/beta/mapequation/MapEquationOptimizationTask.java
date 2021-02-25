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
    private final Map<Long, Map<Long, Double>> predecessors;
    private final Map<Long, Map<Long, Double>> successors;
    private final RelationshipIterator localGraph;
    private final long batchStart;
    private final long batchEnd;
    private final long color;
    private final double totalNodeWeight;
    private final Map<Integer, InfoNode> infoNodes;
    private final HugeLongArray colors;
    private final HugeDoubleArray nodeStrengths;
    private final ProgressLogger progressLogger;
    private final HugeLongArray currentCommunities;
    private final HugeLongArray nextCommunities;
    private final Map<Integer, List<InfoNode>> communityUpdatesMerge;
    private final Map<Integer, List<InfoNode>> communityUpdatesRemove;
    private final HugeDoubleArray flowDistribution;

    MapEquationOptimizationTask(
        Graph graph,
        Map<Long, Map<Long, Double>> predecessors,
        Map<Long, Map<Long, Double>> successors,
        long batchStart,
        long batchEnd,
        long color,
        double totalNodeWeight,
        HugeLongArray colors,
        HugeDoubleArray nodeStrengths,
        Map<Integer, InfoNode> infoNodes,
        HugeLongArray currentCommunities,
        HugeLongArray nextCommunities,
        Map<Integer, List<InfoNode>> communityUpdatesMerge,
        Map<Integer, List<InfoNode>> communityUpdatesRemove,
        HugeDoubleArray flowDistribution,
        ProgressLogger progressLogger
    ) {
        this.graph = graph;
        this.predecessors = predecessors;
        this.successors = successors;
        this.batchStart = batchStart;
        this.batchEnd = batchEnd;
        this.infoNodes = infoNodes;
        this.color = color;
        this.nodeStrengths = nodeStrengths;
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
            part3Old = inner * Math.log(inner) / Math.log(2);
        }

        for (long nodeId = batchStart; nodeId < batchEnd; nodeId++)
        {
            // Seems like this task is only trying to move nodes that have a certain colour. I think the idea is that,
            // since neighbouring nodes do not have the same colour, we can avoid some bad situations with this. Not
            // sure how much this addresses problems that come with parallelisation.
            if (colors.get(nodeId) != color) {
                continue;
            }

            final long currentCommunity = currentCommunities.get(nodeId);
            final double nodeFlow = flowDistribution.get(nodeId);

            // figure out which communities are neighbours of this community because we will try to move this node there
            Set<Long> neighbourCommunities = new TreeSet<>();
            for (long neighbour : predecessors.get(nodeId).keySet())
                neighbourCommunities.add(currentCommunities.get(neighbour));
            for (long neighbour : successors.get(nodeId).keySet())
                neighbourCommunities.add(currentCommunities.get(neighbour));

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

                for (Long t : successors.get(nodeId).keySet())
                {
                    double w = nodeFlow * successors.get(nodeId).get(t) / nodeStrengths.get(nodeId);
                    // the link is within currentCommunity [case (a)]
                    //  -> it will be between currentCommunity and candidateCommunity after moving
                    if (currentCommunities.get(t) == currentCommunity) {
                        deltaCurrentCommunity.addEnterFlow(w);
                        deltaCandidateCommunity.addExitFlow(w);
                    }
                    // the link is between currentCommunity and candidateCommunity [case (b)]
                    //  -> it will be within candidateCommunity after moving
                    else if (currentCommunities.get(t) == candidateCommunity) {
                        deltaCurrentCommunity.addExitFlow(-w);
                        deltaCandidateCommunity.addEnterFlow(-w);
                    }
                    // the link is between currentCommunity and some other community that is not candidateCommunity
                    //  -> it will be between candidateCommunity and that other community after moving
                    else {
                        deltaCurrentCommunity.addExitFlow(-w);
                        deltaCandidateCommunity.addExitFlow(w);
                    }
                }

                for (Long s : predecessors.get(nodeId).keySet())
                {
                    double w = flowDistribution.get(s) * predecessors.get(nodeId).get(s) / nodeStrengths.get(s);
                    // the link is within currentCommunity [case (a)]
                    //  -> it will be between currentCommunity and candidateCommunity after moving
                    if (currentCommunities.get(s) == currentCommunity) {
                        deltaCurrentCommunity.addExitFlow(w);
                        deltaCandidateCommunity.addEnterFlow(w);
                    }
                    // the link is between currentCommunity and candidateCommunity [case (b)]
                    //  -> it will be within candidateCommunity after moving
                    else if (currentCommunities.get(s) == candidateCommunity) {
                        deltaCurrentCommunity.addEnterFlow(-w);
                        deltaCandidateCommunity.addExitFlow(-w);
                    }
                    // the link is between currentCommunity and some other community that is not candidateCommunity
                    //  -> it will be between candidateCommunity and that other community after moving
                    else {
                        deltaCurrentCommunity.addEnterFlow(-w);
                        deltaCandidateCommunity.addEnterFlow(w);
                    }
                }

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
                    part3New = inner * Math.log(inner) / Math.log(2);
                }
                currentGain += part3Old - part3New;

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
