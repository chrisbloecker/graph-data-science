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

import org.neo4j.graphalgo.AlgoBaseProc;
import org.neo4j.graphalgo.CommunityProcCompanion;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.api.nodeproperties.LongArrayNodeProperties;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;
import org.neo4j.graphalgo.result.AbstractCommunityResultBuilder;
import org.neo4j.graphalgo.result.AbstractResultBuilder;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;

final class InfomapProc {

    static final String INFOMAP_DESCRIPTION =
        "The Infomap method for community detection is an algorithm for detecting communities in networks.";

    private InfomapProc() {}

    static <CONFIG extends InfomapBaseConfig> NodeProperties nodeProperties(
        AlgoBaseProc.ComputationResult<Infomap, Infomap, CONFIG> computationResult,
        String resultProperty,
        AllocationTracker tracker
    ) {
        var config = computationResult.config();
        var includeIntermediateCommunities = config.includeIntermediateCommunities();
        if (!includeIntermediateCommunities) {
            return CommunityProcCompanion.nodeProperties(
                computationResult,
                resultProperty,
                computationResult.result().finalDendrogram().asNodeProperties(),
                tracker
            );
        } else {
            return (LongArrayNodeProperties) computationResult.result()::getCommunities;
        }
    }

    static <PROC_RESULT, CONFIG extends InfomapBaseConfig> AbstractResultBuilder<PROC_RESULT> resultBuilder(
        InfomapResultBuilder<PROC_RESULT> procResultBuilder,
        AlgoBaseProc.ComputationResult<Infomap, Infomap, CONFIG> computeResult
    ) {
        Infomap result = computeResult.result();
        boolean nonEmpty = !computeResult.isGraphEmpty();

        return procResultBuilder
            .withLevels(nonEmpty ? result.levels() : 0)
            .withCodelength(nonEmpty ? result.codelengths()[result.levels() - 1] : 0)
            .withCodelengths(nonEmpty ? result.codelengths() : new double[0])
            .withCommunityFunction(nonEmpty ? result::getCommunity : null);
    }

    abstract static class InfomapResultBuilder<PROC_RESULT> extends AbstractCommunityResultBuilder<PROC_RESULT> {

        long levels = -1;
        double[] codelengths = new double[]{};
        double codelength = -1;

        InfomapResultBuilder(
            ProcedureCallContext context,
            int concurrency,
            AllocationTracker tracker
        ) {
            super(context, concurrency, tracker);
        }

        InfomapResultBuilder<PROC_RESULT> withLevels(long levels) {
            this.levels = levels;
            return this;
        }

        InfomapResultBuilder<PROC_RESULT> withCodelengths(double[] codelengths) {
            this.codelengths = codelengths;
            return this;
        }

        InfomapResultBuilder<PROC_RESULT> withCodelength(double codelength) {
            this.codelength = codelength;
            return this;
        }
    }
}
