/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Calibrates only the probabilistic thresholds used while adding extracted graph data.
 *
 * <p>Structural safety rules are deliberately absent from the configurable surface. They are
 * enumerated by {@link HardInvariant} and are always present in every resolved snapshot.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphAdditionCalibration {

    public enum Profile {
        STANDARD,
        RECALL_BIASED,
        CONSERVATIVE
    }

    /** Non-tunable rules enforced independently of probabilistic calibration. */
    public enum HardInvariant {
        PROVENANCE_AND_EVIDENCE,
        SCHEMA_VALIDITY,
        MUST_NOT_MERGE,
        ENTITY_PURITY,
        COMPLETE_RELATION_ENDPOINTS
    }

    private Profile profile;
    private Double rigidity;
    private Double candidateMinScore;
    private Double extractionMinConfidence;
    private Double persistenceMinConfidence;
    private Double stringIdentitySimilarity;
    private Double embeddingIdentitySimilarity;

    public static GraphAdditionCalibration standard() {
        return GraphAdditionCalibration.builder().profile(Profile.STANDARD).rigidity(1.0).build();
    }

    public static GraphAdditionCalibration recallBiased() {
        return GraphAdditionCalibration.builder().profile(Profile.RECALL_BIASED).rigidity(1.0).build();
    }

    public static GraphAdditionCalibration conservative() {
        return GraphAdditionCalibration.builder().profile(Profile.CONSERVATIVE).rigidity(1.0).build();
    }

    public Resolved resolve(double legacyCandidateMinScore,
                            double legacyExtractionMinConfidence,
                            double legacyPersistenceMinConfidence,
                            double legacyStringIdentitySimilarity,
                            double legacyEmbeddingIdentitySimilarity) {
        validateThreshold("legacyCandidateMinScore", legacyCandidateMinScore);
        validateThreshold("legacyExtractionMinConfidence", legacyExtractionMinConfidence);
        validateThreshold("legacyPersistenceMinConfidence", legacyPersistenceMinConfidence);
        validateThreshold("legacyStringIdentitySimilarity", legacyStringIdentitySimilarity);
        validateThreshold("legacyEmbeddingIdentitySimilarity", legacyEmbeddingIdentitySimilarity);

        Profile effectiveProfile = profile == null ? Profile.STANDARD : profile;
        double effectiveRigidity = rigidity == null ? 1.0 : rigidity;
        validateThreshold("rigidity", effectiveRigidity);

        double direction = switch (effectiveProfile) {
            case STANDARD -> 0.0;
            case RECALL_BIASED -> -1.0;
            case CONSERVATIVE -> 1.0;
        };

        return new Resolved(
                effectiveProfile,
                effectiveRigidity,
                explicitOrShifted("candidateMinScore", candidateMinScore,
                        legacyCandidateMinScore, direction * 0.20 * effectiveRigidity),
                explicitOrShifted("extractionMinConfidence", extractionMinConfidence,
                        legacyExtractionMinConfidence, direction * 0.15 * effectiveRigidity),
                explicitOrShifted("persistenceMinConfidence", persistenceMinConfidence,
                        legacyPersistenceMinConfidence, direction * 0.20 * effectiveRigidity),
                explicitOrShifted("stringIdentitySimilarity", stringIdentitySimilarity,
                        legacyStringIdentitySimilarity, direction * 0.10 * effectiveRigidity),
                explicitOrShifted("embeddingIdentitySimilarity", embeddingIdentitySimilarity,
                        legacyEmbeddingIdentitySimilarity, direction * 0.08 * effectiveRigidity),
                hardInvariants());
    }

    public static Resolved legacy(double candidateMinScore,
                                  double extractionMinConfidence,
                                  double persistenceMinConfidence,
                                  double stringIdentitySimilarity,
                                  double embeddingIdentitySimilarity) {
        return standard().resolve(candidateMinScore, extractionMinConfidence,
                persistenceMinConfidence, stringIdentitySimilarity, embeddingIdentitySimilarity);
    }

    public static Set<HardInvariant> hardInvariants() {
        return Collections.unmodifiableSet(EnumSet.allOf(HardInvariant.class));
    }

    private static double explicitOrShifted(String name, Double override, double legacy, double shift) {
        if (override != null) {
            validateThreshold(name, override);
            return override;
        }
        return Math.max(0.0, Math.min(1.0, legacy + shift));
    }

    private static void validateThreshold(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and within [0,1]: " + value);
        }
    }

    /** Immutable, fully validated thresholds consumed by production graph-addition stages. */
    public record Resolved(
            Profile profile,
            double rigidity,
            double candidateMinScore,
            double extractionMinConfidence,
            double persistenceMinConfidence,
            double stringIdentitySimilarity,
            double embeddingIdentitySimilarity,
            Set<HardInvariant> hardInvariants) {

        public Resolved {
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
            validateThreshold("rigidity", rigidity);
            validateThreshold("candidateMinScore", candidateMinScore);
            validateThreshold("extractionMinConfidence", extractionMinConfidence);
            validateThreshold("persistenceMinConfidence", persistenceMinConfidence);
            validateThreshold("stringIdentitySimilarity", stringIdentitySimilarity);
            validateThreshold("embeddingIdentitySimilarity", embeddingIdentitySimilarity);
            if (hardInvariants == null || !hardInvariants.equals(GraphAdditionCalibration.hardInvariants())) {
                throw new IllegalArgumentException("hard invariants are fixed and may not be changed");
            }
            hardInvariants = GraphAdditionCalibration.hardInvariants();
        }
    }
}
