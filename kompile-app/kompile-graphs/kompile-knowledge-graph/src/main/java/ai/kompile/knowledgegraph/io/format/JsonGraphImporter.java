/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.format;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

public final class JsonGraphImporter {

    private final ObjectMapper mapper;

    public JsonGraphImporter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public PortableGraph parse(InputStream stream) throws IOException {
        return mapper.readValue(stream, PortableGraph.class);
    }

    public PortableGraph parse(byte[] bytes) throws IOException {
        return mapper.readValue(bytes, PortableGraph.class);
    }
}
