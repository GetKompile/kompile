/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.io;

import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.io.model.PortableNamedGraph;
import ai.kompile.knowledgegraph.repository.NamedGraphRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Exports/imports the {@link NamedGraph} registry as portable JSON so a named graph's
 * identity (name, description, ontologyType, {@code schemaJson}, hierarchy) travels with a
 * cloned project. Graph nodes only persist a {@code namedGraphId} string; this carries the
 * rows those ids point at. Import preserves {@code graphId} and is idempotent (skips rows
 * that already exist).
 */
@Service
public class NamedGraphPortability {

    private static final Logger log = LoggerFactory.getLogger(NamedGraphPortability.class);

    private final NamedGraphRepository repository;
    private final ObjectMapper mapper;

    public NamedGraphPortability(NamedGraphRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Serialize all named-graph rows; {@code null} when there are none. */
    public byte[] export() {
        List<NamedGraph> graphs = repository.findAll();
        if (graphs.isEmpty()) {
            return null;
        }
        List<PortableNamedGraph> portable = new ArrayList<>(graphs.size());
        for (NamedGraph g : graphs) {
            portable.add(new PortableNamedGraph(
                    g.getGraphId(),
                    g.getName(),
                    g.getDescription(),
                    g.getOntologyType(),
                    g.getSchemaJson(),
                    g.getMetadataJson(),
                    g.getFactSheetId(),
                    g.getParentGraph() != null ? g.getParentGraph().getGraphId() : null,
                    g.getOntologySchemaId(),
                    g.getOntologyVersion()));
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(portable);
        } catch (Exception e) {
            log.warn("Failed to serialize named graphs: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Recreate named-graph rows from a portable payload. Two passes: create rows
     * (preserving {@code graphId}), then link parents once all rows exist.
     *
     * @return number of rows created
     */
    public int importGraphs(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        List<PortableNamedGraph> portable;
        try {
            portable = mapper.readValue(data, new TypeReference<List<PortableNamedGraph>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse named graphs: {}", e.getMessage());
            return 0;
        }
        int created = 0;
        for (PortableNamedGraph p : portable) {
            if (p.graphId() == null || repository.existsByGraphId(p.graphId())) {
                continue;
            }
            NamedGraph g = NamedGraph.builder()
                    .graphId(p.graphId())
                    .name(p.name() != null ? p.name() : p.graphId())
                    .description(p.description())
                    .ontologyType(p.ontologyType())
                    .schemaJson(p.schemaJson())
                    .metadataJson(p.metadataJson())
                    .factSheetId(p.factSheetId())
                    .ontologySchemaId(p.ontologySchemaId())
                    .ontologyVersion(p.ontologyVersion())
                    .build();
            repository.save(g);
            created++;
        }
        for (PortableNamedGraph p : portable) {
            if (p.parentGraphId() == null || p.graphId() == null) {
                continue;
            }
            repository.findByGraphId(p.graphId()).ifPresent(child -> {
                if (child.getParentGraph() == null) {
                    repository.findByGraphId(p.parentGraphId()).ifPresent(parent -> {
                        child.setParentGraph(parent);
                        repository.save(child);
                    });
                }
            });
        }
        return created;
    }
}
