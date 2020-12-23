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
package org.neo4j.graphalgo.core.utils.export.file;

import org.neo4j.graphalgo.api.GraphStore;
import org.neo4j.graphalgo.compat.Neo4jProxy;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.concurrency.Pools;
import org.neo4j.graphalgo.core.utils.export.GraphStoreExporter;
import org.neo4j.graphalgo.core.utils.export.GraphStoreInput;
import org.neo4j.graphalgo.core.utils.export.file.csv.CsvNodeVisitor;
import org.neo4j.graphalgo.core.utils.export.file.csv.CsvRelationshipVisitor;
import org.neo4j.internal.batchimport.InputIterator;
import org.neo4j.internal.batchimport.input.Collector;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class GraphStoreToFileExporter extends GraphStoreExporter<GraphStoreToFileExporterConfig> {

    private final VisitorProducer<NodeVisitor> nodeVisitorSupplier;
    private final VisitorProducer<RelationshipVisitor> relationshipVisitorSupplier;

    public static GraphStoreToFileExporter csv(
        GraphStore graphStore,
        GraphStoreToFileExporterConfig config,
        GraphDatabaseAPI api
    ) {
        var importFolder = Neo4jProxy.homeDirectory(api.databaseLayout()).resolve("import");
        DIRECTORY_IS_WRITABLE.validate(importFolder);

        var importPath = importFolder.resolve(config.exportName());
        if (importPath.toFile().exists()) {
            throw new IllegalArgumentException("The specified import directory already exists.");
        }
        try {
            Files.createDirectories(importPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not create import directory", e);
        }

        return csv(graphStore, config, importPath);
    }

    public static GraphStoreToFileExporter csv(
        GraphStore graphStore,
        GraphStoreToFileExporterConfig config,
        Path importPath
    ) {
        Set<String> headerFiles = ConcurrentHashMap.newKeySet();

        return new GraphStoreToFileExporter(
            graphStore,
            config,
            (index) -> new CsvNodeVisitor(importPath, graphStore.schema().nodeSchema(), headerFiles, index),
            (index) -> new CsvRelationshipVisitor(importPath, graphStore.schema().relationshipSchema(), headerFiles, index)
        );
    }

    private GraphStoreToFileExporter(
        GraphStore graphStore,
        GraphStoreToFileExporterConfig config,
        VisitorProducer<NodeVisitor> nodeVisitorSupplier,
        VisitorProducer<RelationshipVisitor> relationshipVisitorSupplier
    ) {
        super(graphStore, config);
        this.nodeVisitorSupplier = nodeVisitorSupplier;
        this.relationshipVisitorSupplier = relationshipVisitorSupplier;
    }

    @Override
    public void export(GraphStoreInput graphStoreInput) {
        exportNodes(graphStoreInput);
        exportRelationships(graphStoreInput);
    }

    private void exportNodes(GraphStoreInput graphStoreInput) {
        var nodeInput = graphStoreInput.nodes(Collector.EMPTY);
        var nodeInputIterator = nodeInput.iterator();

        var tasks = ParallelUtil.tasks(
            config.writeConcurrency(),
            (index) -> new ImportRunner(nodeVisitorSupplier.apply(index), nodeInputIterator)
        );

        ParallelUtil.runWithConcurrency(config.writeConcurrency(), tasks, Pools.DEFAULT);
    }

    private void exportRelationships(GraphStoreInput graphStoreInput) {
        var relationshipInput = graphStoreInput.relationships(Collector.EMPTY);
        var relationshipInputIterator = relationshipInput.iterator();

        var tasks = ParallelUtil.tasks(
            config.writeConcurrency(),
            (index) -> new ImportRunner(relationshipVisitorSupplier.apply(index), relationshipInputIterator)
        );

        ParallelUtil.runWithConcurrency(config.writeConcurrency(), tasks, Pools.DEFAULT);
    }

    private static final class ImportRunner implements Runnable {
        private final ElementVisitor<?, ?, ?> visitor;
        private final InputIterator inputIterator;

        private ImportRunner(
            ElementVisitor<?, ?, ?> visitor,
            InputIterator inputIterator
        ) {
            this.visitor = visitor;
            this.inputIterator = inputIterator;
        }

        @Override
        public void run() {
            try (var chunk = inputIterator.newChunk()) {
                while (inputIterator.next(chunk)) {
                    while (chunk.next(visitor)) {

                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            visitor.close();
        }
    }

    private interface VisitorProducer<VISITOR> extends Function<Integer, VISITOR> {
    }
}
