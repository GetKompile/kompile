/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Portable form of a {@link ai.kompile.knowledgegraph.domain.NamedGraph} registry row.
 * Graph nodes only carry a {@code namedGraphId} string; without exporting these rows a
 * cloned project would lose each named graph's identity (name, description, ontologyType,
 * {@code schemaJson}, its bound ontology) and parent/child hierarchy. Identity is preserved by
 * {@code graphId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortableNamedGraph(
        String graphId,
        String name,
        String description,
        String ontologyType,
        String schemaJson,
        String metadataJson,
        Long factSheetId,
        String parentGraphId,
        String ontologySchemaId,
        Integer ontologyVersion
) {}
