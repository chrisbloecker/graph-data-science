/*
 * Copyright (c) 2017-2021 "Neo4j,"
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
package org.neo4j.gds.ml.nodemodels.logisticregression;

import org.neo4j.gds.ml.TrainingConfig;
import org.neo4j.graphalgo.annotation.Configuration;
import org.neo4j.graphalgo.annotation.ValueClass;
import org.neo4j.graphalgo.config.FeaturePropertiesConfig;
import org.neo4j.graphalgo.core.CypherMapWrapper;

import java.util.List;
import java.util.Map;

@ValueClass
@Configuration
public interface MultiClassNLRTrainConfig extends FeaturePropertiesConfig, TrainingConfig {

    @Configuration.Parameter
    List<String> featureProperties();

    @Configuration.Parameter
    String targetProperty();

    double penalty();

    static MultiClassNLRTrainConfig of(
        List<String> featureProperties,
        String targetProperty,
        Map<String, Object> params
    ) {
        return new MultiClassNLRTrainConfigImpl(
            featureProperties,
            targetProperty,
            CypherMapWrapper.create(params)
        );
    }

}