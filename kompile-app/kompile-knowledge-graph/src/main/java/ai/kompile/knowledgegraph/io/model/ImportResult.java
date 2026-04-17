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

import java.util.List;

public record ImportResult(
        String format,
        int nodesCreated,
        int nodesUpdated,
        int edgesCreated,
        int errors,
        List<String> errorMessages
) {}
