/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.staging.sdx;

import ai.kompile.staging.config.SdxStagingProperties;
import ai.kompile.staging.config.StagingPropertyKeys;
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.StagingCancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fail-closed staging adapter for DL4J repositories that predate nd4j-sdx-model.
 *
 * <p>Untargeted model staging remains available. Requests for compiled mobile SDX
 * artifacts fail explicitly until the configured repository publishes the canonical
 * compiler/cache artifact.</p>
 */
@Service
public class SdxProjectOutputService {
    public static final String OUTPUT_MODEL = "model";
    public static final String OUTPUT_KPROJECT = "kproject";
    public static final String QUANTIZATION_NONE = "none";
    public static final String QUANTIZATION_INT8 = "int8";
    public static final String QUANTIZATION_INT8_PER_TENSOR = "int8-per-tensor";
    public static final String QUANTIZATION_INT8_PER_CHANNEL = "int8-per-channel";

    private static final String UNAVAILABLE_MESSAGE =
            "Mobile SDX target compilation is unavailable because the configured "
                    + "DL4J Maven repository does not publish nd4j-sdx-model";

    @Autowired
    public SdxProjectOutputService(
            SdxStagingProperties properties,
            @Value(StagingPropertyKeys.MODELS_DIR_VALUE) String modelsDir,
            @Value("${kompile.staging.project-dir:}") String projectDir) {
        // Preserve the production bean contract while deliberately omitting the unavailable
        // upstream compiler. Validation and untargeted staging do not require local state here.
    }

    public static boolean isProjectOutputRequested(DownloadRequest request) {
        return request != null
                && OUTPUT_KPROJECT.equals(normalizeOutputFormat(request.getOutputFormat()));
    }

    public static boolean isTargetOutputRequested(DownloadRequest request) {
        if (request == null) {
            return false;
        }
        String outputFormat = normalizeOutputFormat(request.getOutputFormat());
        return OUTPUT_KPROJECT.equals(outputFormat)
                || (OUTPUT_MODEL.equals(outputFormat)
                        && request.getTargetProfile() != null
                        && !request.getTargetProfile().isBlank());
    }

    public Path createProject(
            Path stagingWorkspace,
            Path canonicalSdz,
            DownloadRequest request) throws IOException {
        throw unavailable();
    }

    public Path createProject(
            Path stagingWorkspace,
            ConversionArtifact conversionArtifact,
            DownloadRequest request,
            StagingCancellation cancellation) throws IOException {
        throw unavailable();
    }

    public Path createOutput(
            Path stagingWorkspace,
            ConversionArtifact conversionArtifact,
            DownloadRequest request,
            StagingCancellation cancellation) throws IOException {
        throw unavailable();
    }

    public static String normalizeOutputFormat(String value) {
        if (value == null || value.isBlank()) {
            return OUTPUT_MODEL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (OUTPUT_MODEL.equals(normalized) || OUTPUT_KPROJECT.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported staging outputFormat: " + value);
    }

    public static String normalizeQuantization(String value) {
        if (value == null || value.isBlank()) {
            return QUANTIZATION_NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (QUANTIZATION_NONE.equals(normalized)) {
            return normalized;
        }
        if (QUANTIZATION_INT8.equals(normalized)
                || QUANTIZATION_INT8_PER_CHANNEL.equals(normalized)
                || QUANTIZATION_INT8_PER_TENSOR.equals(normalized)) {
            return QUANTIZATION_INT8;
        }
        throw new IllegalArgumentException(
                "Unsupported SDX quantizationProfile: " + value
                        + ". Supported values are none and int8.");
    }

    public static String normalizeTargetProfile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "targetProfile is required for downloadable mobile SDX output");
        }
        throw new IllegalArgumentException(UNAVAILABLE_MESSAGE);
    }

    public static String normalizeTargetSoc(String targetProfile, String value) {
        throw new IllegalArgumentException(UNAVAILABLE_MESSAGE);
    }

    private static IOException unavailable() {
        return new IOException(UNAVAILABLE_MESSAGE);
    }
}
