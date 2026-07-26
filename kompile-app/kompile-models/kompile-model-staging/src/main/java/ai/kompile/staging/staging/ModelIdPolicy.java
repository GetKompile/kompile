/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.staging;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Single model identifier and filesystem-containment contract for staging entry points.
 */
public final class ModelIdPolicy {
    public static final Pattern SAFE_MODEL_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private ModelIdPolicy() {
    }

    public static String requireValid(String modelId) {
        if (modelId == null || !SAFE_MODEL_ID.matcher(modelId).matches()) {
            throw new IllegalArgumentException(
                    "modelId must match " + SAFE_MODEL_ID.pattern());
        }
        return modelId;
    }

    public static Path contained(Path root, String modelId) {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(requireValid(modelId)).normalize();
        if (!candidate.startsWith(normalizedRoot) || candidate.equals(normalizedRoot)) {
            throw new IllegalArgumentException("modelId escapes its staging root");
        }
        return candidate;
    }
}
