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
import org.neo4j.graphalgo.WriteProc;
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

public class InfomapWriteProc extends WriteProc<Infomap, Infomap, InfomapWriteProc.WriteResult, InfomapWriteConfig> {

    @Procedure(value = "gds.infomap.write", mode = WRITE)
    @Description(INFOMAP_DESCRIPTION)
    public Stream<WriteResult> write(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return write(compute(graphNameOrConfig, configuration));
    }

    @Procedure(value = "gds.infomap.write.estimate", mode = READ)
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> estimate(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return computeEstimate(graphNameOrConfig, configuration);
    }

    @Override
    protected NodeProperties nodeProperties(ComputationResult<Infomap, Infomap, InfomapWriteConfig> computationResult) {
        return InfomapProc.nodeProperties(computationResult, computationResult.config().writeProperty(), allocationTracker());
    }

    @Override
    protected AbstractResultBuilder<WriteResult> resultBuilder(ComputationResult<Infomap, Infomap, InfomapWriteConfig> computeResult) {
        return InfomapProc.resultBuilder(new WriteResult.Builder(
            callContext,
            computeResult.config().concurrency(),
            allocationTracker()
        ), computeResult);
    }

    @Override
    protected InfomapWriteConfig newConfig(
        String username,
        Optional<String> graphName,
        Optional<GraphCreateConfig> maybeImplicitCreate,
        CypherMapWrapper config
    ) {
        return InfomapWriteConfig.of(username, graphName, maybeImplicitCreate, config);
    }

    @Override
    protected AlgorithmFactory<Infomap, InfomapWriteConfig> algorithmFactory() {
        return new InfomapFactory<>();
    }

    public static final class WriteResult extends InfomapStatsProc.StatsResult {

        public final long writeMillis;
        public final long nodePropertiesWritten;

        WriteResult(
            double codelength,
            List<Double> codelengths,
            long ranLevels,
            long communityCount,
            Map<String, Object> communityDistribution,
            long createMillis,
            long computeMillis,
            long postProcessingMillis,
            long writeMillis,
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
            this.writeMillis = writeMillis;
            this.nodePropertiesWritten = nodePropertiesWritten;
        }

        static class Builder extends InfomapProc.InfomapResultBuilder<WriteResult> {

            Builder(ProcedureCallContext context, int concurrency, AllocationTracker tracker) {
                super(context, concurrency, tracker);
            }

            @Override
            protected WriteResult buildResult() {
                return new WriteResult(
                    codelength,
                    Arrays.stream(codelengths).boxed().collect(Collectors.toList()),
                    levels,
                    maybeCommunityCount.orElse(0L),
                    communityHistogramOrNull(),
                    createMillis,
                    computeMillis,
                    postProcessingDuration,
                    writeMillis,
                    nodePropertiesWritten,
                    config.toMap()
                );
            }
        }
    }
}
