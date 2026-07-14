/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed serving ABI for a model that emits a completed audio waveform.
 *
 * <p>The first production backend intentionally supports an end-to-end SameDiff
 * graph whose output is already a normalized mono waveform. A vocoder, codec
 * conversion, normalization, multi-speaker selection, and arbitrary inference
 * parameters are separate model-family contracts rather than implicit behavior
 * in the staging service.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class AudioSynthesisConfig {

    public static final String SAMEDIFF_WAVEFORM_BACKEND = "samediff_waveform";
    public static final String HUGGING_FACE_TOKENIZER = "hugging_face";
    public static final String UTF8_BYTES_TOKENIZER = "utf8_bytes";

    @Builder.Default
    private String backend = SAMEDIFF_WAVEFORM_BACKEND;

    @JsonProperty("tokenizer_type")
    @Builder.Default
    private String tokenizerType = HUGGING_FACE_TOKENIZER;

    @JsonProperty("tokenizer_file")
    @Builder.Default
    private String tokenizerFile = "tokenizer.json";

    @JsonProperty("token_ids_input")
    @Builder.Default
    private String tokenIdsInput = "input_ids";

    @JsonProperty("attention_mask_input")
    private String attentionMaskInput;

    @JsonProperty("token_lengths_input")
    private String tokenLengthsInput;

    @JsonProperty("waveform_output")
    @Builder.Default
    private String waveformOutput = "waveform";

    @JsonProperty("confidence_output")
    private String confidenceOutput;

    @JsonProperty("token_data_type")
    @Builder.Default
    private String tokenDataType = "int64";

    @JsonProperty("add_special_tokens")
    @Builder.Default
    private boolean addSpecialTokens = true;

    @JsonProperty("sample_rate_hz")
    @Builder.Default
    private int sampleRateHz = 22_050;

    @Builder.Default
    private int channels = 1;

    @JsonProperty("sample_format")
    @Builder.Default
    private String sampleFormat = "pcm_s16le";

    @JsonProperty("media_type")
    @Builder.Default
    private String mediaType = "audio/wav";

    @JsonProperty("file_extension")
    @Builder.Default
    private String fileExtension = ".wav";

    @JsonProperty("max_input_tokens")
    @Builder.Default
    private int maxInputTokens = 2_048;

    @JsonProperty("max_output_samples")
    @Builder.Default
    private long maxOutputSamples = 6_615_000L;

    /**
     * This backend is single-voice by design. The value is an opaque registry
     * label, never a filesystem path or remote identifier.
     */
    private String voice;

    /**
     * Fixed BCP-47-style language label supported by this registered graph.
     */
    private String language;

    @JsonProperty("default_confidence")
    @Builder.Default
    private double defaultConfidence = 1.0d;
}
