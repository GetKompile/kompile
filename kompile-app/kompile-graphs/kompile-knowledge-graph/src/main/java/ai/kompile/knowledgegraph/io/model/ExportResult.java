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

public record ExportResult(
        String format,
        int nodesExported,
        int edgesExported,
        byte[] data,
        String contentType,
        String suggestedFilename
) {}
