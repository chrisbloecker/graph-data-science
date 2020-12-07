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

import org.jetbrains.annotations.Nullable;
import org.neo4j.graphalgo.AlgoBaseProc;
import org.neo4j.graphalgo.AlgorithmFactory;
import org.neo4j.graphalgo.StreamProc;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.config.GraphCreateConfig;
import org.neo4j.graphalgo.core.CypherMapWrapper;
import org.neo4j.graphalgo.results.MemoryEstimateResult;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.neo4j.graphalgo.infomap.InfomapProc.INFOMAP_DESCRIPTION;
import static org.neo4j.procedure.Mode.READ;

public class InfomapStreamProc extends StreamProc<Infomap, Infomap, InfomapStreamProc.StreamResult, InfomapStreamConfig> {

    @Procedure(value = "gds.infomap.stream", mode = READ)
    @Description(INFOMAP_DESCRIPTION)
    public Stream<StreamResult> stream(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return stream(compute(graphNameOrConfig, configuration));
    }

    @Procedure(value = "gds.infomap.stream.estimate", mode = READ)
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> estimate(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return computeEstimate(graphNameOrConfig, configuration);
    }

    @Override
    protected InfomapStreamConfig newConfig(
        String username,
        Optional<String> graphName,
        Optional<GraphCreateConfig> maybeImplicitCreate,
        CypherMapWrapper config
    ) {
        return InfomapStreamConfig.of(username, graphName, maybeImplicitCreate, config);
    }

    @Override
    protected AlgorithmFactory<Infomap, InfomapStreamConfig> algorithmFactory() {
        return new InfomapFactory<>();
    }

    @Override
    protected Stream<StreamResult> stream(AlgoBaseProc.ComputationResult<Infomap, Infomap, InfomapStreamConfig> computationResult) {
        return runWithExceptionLogging("Graph streaming failed", () -> {
            Graph graph = computationResult.graph();

            return LongStream
                .range(0, graph.nodeCount())
                .boxed()
                .map((nodeId) -> {
                    boolean includeIntermediateCommunities = computationResult
                        .config()
                        .includeIntermediateCommunities();
                    Infomap infomap = computationResult.result();
                    long[] communities = includeIntermediateCommunities ? infomap.getCommunities(nodeId) : null;

                    return new StreamResult(graph.toOriginalNodeId(nodeId), communities, infomap.getCommunity(nodeId));
                });
        });
    }

    @Override
    protected NodeProperties nodeProperties(ComputationResult<Infomap, Infomap, InfomapStreamConfig> computationResult) {
        return InfomapProc.nodeProperties(computationResult, UUID.randomUUID().toString(), allocationTracker());
    }

    @Override
    protected StreamResult streamResult(
        long originalNodeId, long internalNodeId, NodeProperties nodeProperties
    ) {
        throw new UnsupportedOperationException("Infomap handles result building individually.");
    }

    public static final class StreamResult {
        public final long nodeId;
        public final long communityId;
        public final List<Long> intermediateCommunityIds;

        StreamResult(long nodeId, @Nullable long[] intermediateCommunityIds, long communityId) {
            this.nodeId = nodeId;
            this.intermediateCommunityIds = intermediateCommunityIds == null ? null : Arrays
                .stream(intermediateCommunityIds)
                .boxed()
                .collect(Collectors.toList());
            this.communityId = communityId;
        }
    }
}
