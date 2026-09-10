/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import java.time.Instant;
import java.util.List;

/** Portable lifecycle values for one hidden physical graph generation. */
public final class GraphGeneration {

    private GraphGeneration() { }

    public record Ref(long factSheetId, String logicalGraphId, String physicalGraphId,
                      String generationId, String expectedActivePhysicalGraphId,
                      long expectedRevision) {
        public Ref {
            if (factSheetId <= 0) throw new IllegalArgumentException("factSheetId must be positive");
            logicalGraphId = requireText(logicalGraphId, "logicalGraphId");
            physicalGraphId = requireText(physicalGraphId, "physicalGraphId");
            generationId = requireText(generationId, "generationId");
            expectedActivePhysicalGraphId = requireText(
                    expectedActivePhysicalGraphId, "expectedActivePhysicalGraphId");
            if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
            if (logicalGraphId.contains("~gen~") || generationId.contains("~gen~")) {
                throw new IllegalArgumentException("logicalGraphId and generationId must not contain '~gen~'");
            }
            String expectedPhysicalId = logicalGraphId + "~gen~" + generationId;
            if (!expectedPhysicalId.equals(physicalGraphId)) {
                throw new IllegalArgumentException(
                        "physicalGraphId must equal logicalGraphId + '~gen~' + generationId");
            }
        }

        public Target target() {
            return new Target(factSheetId, logicalGraphId, physicalGraphId, generationId);
        }
    }

    /** Routing identity carried on every generation-scoped graph RPC. */
    public record Target(long factSheetId, String logicalGraphId, String physicalGraphId,
                         String generationId) {
        public Target {
            if (factSheetId <= 0) throw new IllegalArgumentException("factSheetId must be positive");
            logicalGraphId = requireText(logicalGraphId, "logicalGraphId");
            physicalGraphId = requireText(physicalGraphId, "physicalGraphId");
            generationId = requireText(generationId, "generationId");
            if (!physicalGraphId.equals(logicalGraphId + "~gen~" + generationId)) {
                throw new IllegalArgumentException("Generation target has a non-canonical physical graph ID");
            }
        }
    }

    /** Authoritative active-pointer tuple stored in the durable generation journal. */
    public record Pointer(long factSheetId, String logicalGraphId, String activePhysicalGraphId,
                          String previousPhysicalGraphId, long revision) {
        public Pointer {
            if (factSheetId <= 0) throw new IllegalArgumentException("factSheetId must be positive");
            logicalGraphId = requireText(logicalGraphId, "logicalGraphId");
            activePhysicalGraphId = requireText(activePhysicalGraphId, "activePhysicalGraphId");
            if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
        }
    }

    public record Validation(boolean valid, int nodeCount, int edgeCount, List<String> errors) {
        public Validation {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    public record Activation(String logicalGraphId, String activePhysicalGraphId,
                             String previousPhysicalGraphId, long revision, Instant activatedAt) { }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
