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

import org.neo4j.graphalgo.AlgorithmFactory;
import org.neo4j.graphalgo.MutatePropertyProc;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.config.GraphCreateConfig;
import org.neo4j.graphalgo.core.CypherMapWrapper;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.result.AbstractResultBuilder;
import org.neo4j.graphalgo.results.MemoryEstimateResult;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.neo4j.graphalgo.infomap.InfomapProc.INFOMAP_DESCRIPTION;
import static org.neo4j.procedure.Mode.READ;
import static org.neo4j.procedure.Mode.WRITE;

public class InfomapMutateProc extends MutatePropertyProc<Infomap, Infomap, InfomapMutateProc.MutateResult, InfomapMutateConfig> {

    @Procedure(value = "gds.infomap.mutate", mode = WRITE)
    @Description(INFOMAP_DESCRIPTION)
    public Stream<MutateResult> write(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return mutate(compute(graphNameOrConfig, configuration));
    }

    @Procedure(value = "gds.infomap.mutate.estimate", mode = READ)
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> estimate(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return computeEstimate(graphNameOrConfig, configuration);
    }

    @Override
    protected InfomapMutateConfig newConfig(
        String username,
        Optional<String> graphName,
        Optional<GraphCreateConfig> maybeImplicitCreate,
        CypherMapWrapper config
    ) {
        return InfomapMutateConfig.of(username, graphName, maybeImplicitCreate, config);
    }

    @Override
    protected AlgorithmFactory<Infomap, InfomapMutateConfig> algorithmFactory() {
        return new InfomapFactory<>();
    }

    @Override
    protected NodeProperties nodeProperties(ComputationResult<Infomap, Infomap, InfomapMutateConfig> computationResult) {
        return InfomapProc.nodeProperties(computationResult, computationResult.config().mutateProperty(), allocationTracker());
    }

    @Override
    protected AbstractResultBuilder<MutateResult> resultBuilder(ComputationResult<Infomap, Infomap, InfomapMutateConfig> computeResult) {
        return InfomapProc.resultBuilder(
            new MutateResult.Builder(callContext, computeResult.config().concurrency(), allocationTracker()),
            computeResult
        );
    }

    public static final class MutateResult extends InfomapStatsProc.StatsResult {

        public final long mutateMillis;
        public final long nodePropertiesWritten;

        MutateResult(
            double codelength,
            List<Double> codelengths,
            long ranLevels,
            long communityCount,
            Map<String, Object> communityDistribution,
            long createMillis,
            long computeMillis,
            long postProcessingMillis,
            long mutateMillis,
            long nodePropertiesWritten,
            Map<String, Object> configuration
        ) {
            super(
                codelength,
                codelengths,
                ranLevels,
                communityCount,
                communityDistribution,
                createMillis,
                computeMillis,
                postProcessingMillis,
                configuration
            );
            this.mutateMillis = mutateMillis;
            this.nodePropertiesWritten = nodePropertiesWritten;
        }

        static class Builder extends InfomapProc.InfomapResultBuilder<MutateResult> {

            Builder(ProcedureCallContext context, int concurrency, AllocationTracker tracker) {
                super(context, concurrency, tracker);
            }

            @Override
            protected MutateResult buildResult() {
                return new MutateResult(
                    codelength,
                    Arrays.stream(codelengths).boxed().collect(Collectors.toList()),
                    levels,
                    maybeCommunityCount.orElse(0L),
                    communityHistogramOrNull(),
                    createMillis,
                    computeMillis,
                    postProcessingDuration,
                    mutateMillis,
                    nodePropertiesWritten,
                    config.toMap()
                );
            }
        }
    }
}
