/*
 * Copyright (c) "Neo4j"
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
package org.neo4j.graphalgo.functions;

import org.neo4j.graphalgo.NodeLabel;
import org.neo4j.graphalgo.api.DefaultValue;
import org.neo4j.graphalgo.api.GraphStore;
import org.neo4j.graphalgo.api.nodeproperties.ValueType;
import org.neo4j.graphalgo.core.loading.GraphStoreCatalog;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;
import org.neo4j.values.storable.DoubleArray;
import org.neo4j.values.storable.FloatArray;
import org.neo4j.values.storable.LongArray;

import java.util.Objects;

import static java.util.Collections.singletonList;
import static org.neo4j.graphalgo.ElementProjection.PROJECT_ALL;
import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;
import static org.neo4j.graphalgo.utils.StringJoining.join;

public class NodePropertyFunc {
    @Context
    public GraphDatabaseAPI api;

    @Context
    public KernelTransaction transaction;

    @UserFunction("gds.util.nodeProperty")
    @Description("Returns a node property value from a named in-memory graph.")
    public Object nodeProperty(
        @Name(value = "graphName") String graphName,
        @Name(value = "nodeId") Number nodeId,
        @Name(value = "propertyKey") String propertyKey,
        @Name(value = "nodeLabel", defaultValue = "*") String nodeLabel
    ) {
        Objects.requireNonNull(graphName);
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(propertyKey);
        Objects.requireNonNull(nodeLabel);

        String username = transaction.subjectOrAnonymous().username();
        GraphStore graphStore = GraphStoreCatalog.get(username, api.databaseId(), graphName).graphStore();
        boolean projectAll = nodeLabel.equals(PROJECT_ALL);

        if (projectAll) {
            long labelsWithProperty = graphStore.nodeLabels().stream()
                .filter(label -> graphStore.hasNodeProperty(singletonList(label), propertyKey))
                .count();

            if (labelsWithProperty == 0) {
                throw new IllegalArgumentException(formatWithLocale(
                    "No node projection with property '%s' exists.",
                    propertyKey
                ));
            }
        } else {
            if (!graphStore.hasNodeProperty(singletonList(NodeLabel.of(nodeLabel)), propertyKey)) {
                throw new IllegalArgumentException(formatWithLocale(
                    "Node projection '%s' does not have property key '%s'. Available keys: %s.",
                    nodeLabel,
                    propertyKey,
                    join(graphStore.nodePropertyKeys(NodeLabel.of(nodeLabel)))
                ));
            }
        }

        long internalId = graphStore.nodes().toMappedNodeId(nodeId.longValue());

        if (internalId == -1) {
            throw new IllegalArgumentException(formatWithLocale("Node id %d does not exist.", nodeId.longValue()));
        }

        var propertyValues = projectAll
            ? graphStore.nodePropertyValues(propertyKey) // builds UnionNodeProperties and returns the first matching property
            : graphStore.nodePropertyValues(NodeLabel.of(nodeLabel), propertyKey);

        if (propertyValues.valueType() == ValueType.DOUBLE) {
            double propertyValue = propertyValues.doubleValue(internalId);
            return Double.isNaN(propertyValue) ? null : propertyValue;
        } else if (propertyValues.valueType() == ValueType.LONG) {
            long longValue = propertyValues.longValue(internalId);
            return longValue == DefaultValue.LONG_DEFAULT_FALLBACK ? DefaultValue.DOUBLE_DEFAULT_FALLBACK : (double) longValue;
        } else if (propertyValues.valueType() == ValueType.LONG_ARRAY) {
            long[] longArray = ((LongArray)propertyValues.value(internalId)).asObjectCopy();
            return longArray == null ? new long[] {} : longArray;
        } else if (propertyValues.valueType() == ValueType.FLOAT_ARRAY) {
            float[] floatArray = ((FloatArray)propertyValues.value(internalId)).asObjectCopy();
            return floatArray == null ? new float[] {} : floatArray;
        } else if (propertyValues.valueType() == ValueType.DOUBLE_ARRAY) {
            double[] doubleArray = ((DoubleArray)propertyValues.value(internalId)).asObjectCopy();
            return doubleArray == null ? new double[] {} : doubleArray;
        } else {
            throw new UnsupportedOperationException(formatWithLocale(
                "Cannot retrieve value from a property with type %s",
                propertyValues.valueType()
            ));
        }
    }
}
