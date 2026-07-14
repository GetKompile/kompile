/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.staging.execution.audio;

import ai.kompile.modelmanager.registry.AudioSynthesisConfig;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.eclipse.deeplearning4j.audio.synthesis.AudioFileGenerator;
import org.eclipse.deeplearning4j.audio.synthesis.PcmWavFileWriter;
import org.eclipse.deeplearning4j.audio.synthesis.SameDiffWaveformGenerator;
import org.nd4j.linalg.api.buffer.DataType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry and filesystem-policy adapter for the reusable samediff-audio
 * text-to-waveform generator.
 */
@Component
public final class SameDiffAudioSynthesisBackend implements AudioSynthesisBackend {

    private static final int MAX_LABEL_LENGTH = 64;

    private final ObjectMapper objectMapper;

    public SameDiffAudioSynthesisBackend(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public boolean supports(ModelEntry model) {
        AudioSynthesisConfig config = model == null ? null : model.getAudioSynthesis();
        return model != null
                && model.getType() == ModelType.AUDIO_SYNTHESIS
                && config != null
                && AudioSynthesisConfig.SAMEDIFF_WAVEFORM_BACKEND.equals(config.getBackend());
    }

    @Override
    public AudioFileGenerator load(ModelEntry model, Path modelDirectory) throws Exception {
        AudioSynthesisConfig config = validateConfig(model);
        Path directory = requireDirectory(modelDirectory);
        Path modelFile = containedRegularFile(directory, model.getModelFile(), "model file");
        Path tokenizerFile = null;
        SameDiffWaveformGenerator.TokenizerType tokenizerType =
                SameDiffWaveformGenerator.TokenizerType.UTF8_BYTES;
        if (AudioSynthesisConfig.HUGGING_FACE_TOKENIZER.equals(config.getTokenizerType())) {
            tokenizerType = SameDiffWaveformGenerator.TokenizerType.HUGGING_FACE;
            tokenizerFile = containedRegularFile(
                    directory, config.getTokenizerFile(), "tokenizer file");
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("backend", config.getBackend());
        evidence.put("registryConfigType", "audio_synthesis");

        return SameDiffWaveformGenerator.builder()
                .modelFile(modelFile)
                .tokenizerType(tokenizerType)
                .tokenizerFile(tokenizerFile)
                .tokenIdsInput(config.getTokenIdsInput())
                .attentionMaskInput(config.getAttentionMaskInput())
                .tokenLengthsInput(config.getTokenLengthsInput())
                .waveformOutput(config.getWaveformOutput())
                .confidenceOutput(config.getConfidenceOutput())
                .tokenDataType("int64".equals(config.getTokenDataType())
                        ? DataType.INT64 : DataType.INT32)
                .addSpecialTokens(config.isAddSpecialTokens())
                .sampleRateHz(config.getSampleRateHz())
                .maxInputTokens(config.getMaxInputTokens())
                .maxOutputSamples(config.getMaxOutputSamples())
                .voice(config.getVoice())
                .language(config.getLanguage())
                .modelId(model.getModelId())
                .modelVersion(model.getEffectiveVersion())
                .configurationVersion(configurationVersion(model, config))
                .defaultConfidence(config.getDefaultConfidence())
                .configurationEvidence(evidence)
                .build();
    }

    private AudioSynthesisConfig validateConfig(ModelEntry model) {
        if (model == null || model.getType() != ModelType.AUDIO_SYNTHESIS) {
            throw new IllegalArgumentException("model must be an audio_synthesis registry entry");
        }
        AudioSynthesisConfig config = Objects.requireNonNull(
                model.getAudioSynthesis(), "audio_synthesis configuration is required");
        requireEquals(AudioSynthesisConfig.SAMEDIFF_WAVEFORM_BACKEND,
                config.getBackend(), "backend");
        if (!AudioSynthesisConfig.HUGGING_FACE_TOKENIZER.equals(config.getTokenizerType())
                && !AudioSynthesisConfig.UTF8_BYTES_TOKENIZER.equals(config.getTokenizerType())) {
            throw new IllegalArgumentException(
                    "tokenizer_type must be hugging_face or utf8_bytes");
        }
        if (AudioSynthesisConfig.HUGGING_FACE_TOKENIZER.equals(config.getTokenizerType())) {
            requireNonBlank(config.getTokenizerFile(), "tokenizer_file");
        }
        requireNonBlank(config.getTokenIdsInput(), "token_ids_input");
        requireNonBlank(config.getWaveformOutput(), "waveform_output");
        if (!"int32".equals(config.getTokenDataType())
                && !"int64".equals(config.getTokenDataType())) {
            throw new IllegalArgumentException("token_data_type must be int32 or int64");
        }
        if (config.getSampleRateHz() < 8_000 || config.getSampleRateHz() > 384_000) {
            throw new IllegalArgumentException("sample_rate_hz is outside the supported range");
        }
        if (config.getChannels() != 1) {
            throw new IllegalArgumentException("the samediff_waveform backend supports mono only");
        }
        requireEquals(PcmWavFileWriter.SAMPLE_FORMAT, config.getSampleFormat(), "sample_format");
        requireEquals(PcmWavFileWriter.MEDIA_TYPE, config.getMediaType(), "media_type");
        requireEquals(".wav", config.getFileExtension(), "file_extension");
        if (config.getMaxInputTokens() <= 0) {
            throw new IllegalArgumentException("max_input_tokens must be positive");
        }
        if (config.getMaxOutputSamples() <= 0) {
            throw new IllegalArgumentException("max_output_samples must be positive");
        }
        requireSafeLabel(config.getVoice(), "voice");
        requireSafeLabel(config.getLanguage(), "language");
        if (!Double.isFinite(config.getDefaultConfidence())
                || config.getDefaultConfidence() < 0.0d
                || config.getDefaultConfidence() > 1.0d) {
            throw new IllegalArgumentException("default_confidence must be between 0 and 1");
        }
        requireNonBlank(model.getModelId(), "model_id");
        requireNonBlank(model.getEffectiveVersion(), "model version");
        requireNonBlank(model.getModelFile(), "model_file");
        return config;
    }

    private String configurationVersion(ModelEntry model, AudioSynthesisConfig config)
            throws IOException {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("modelId", model.getModelId());
        canonical.put("modelVersion", model.getEffectiveVersion());
        canonical.put("modelChecksum", Objects.toString(model.getChecksum(), ""));
        canonical.put("audioSynthesis", config);
        byte[] bytes = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(canonical);
        return "sha256:" + HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static Path requireDirectory(Path directory) throws IOException {
        Path normalized = Objects.requireNonNull(directory, "modelDirectory")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("modelDirectory must be a regular directory");
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path containedRegularFile(Path directory, String configuredPath, String label)
            throws IOException {
        String value = requireNonBlank(configuredPath, label);
        Path candidate = directory.resolve(value).normalize();
        if (!candidate.startsWith(directory)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException(
                    label + " is missing, unsafe, or outside the model directory");
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(directory)) {
            throw new IllegalArgumentException(label + " escaped the model directory");
        }
        return real;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireEquals(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static void requireSafeLabel(String value, String field) {
        requireNonBlank(value, field);
        if (value.length() > MAX_LABEL_LENGTH
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(field + " must be an opaque registry label");
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
