/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.config;

import ai.kompile.staging.download.TextModelAssetMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resource limits applied before untrusted model assets enter conversion or SDX compilation.
 */
@Component
@ConfigurationProperties(prefix = "kompile.staging.limits")
public class StagingAssetLimits {
    private long modelBytes = 8L * 1024 * 1024 * 1024;
    private long tokenizerBytes = 64L * 1024 * 1024;
    private long configBytes = 8L * 1024 * 1024;
    private long totalBytes = 9L * 1024 * 1024 * 1024;
    private int localMaxFiles = 64;
    private int localMaxDepth = 4;
    private int maxRedirects = 5;
    private long cancellationWaitMillis = 60_000L;

    public long maxBytesFor(String assetKey) {
        if (TextModelAssetMap.MODEL.equals(assetKey)
                || (assetKey != null && assetKey.startsWith("vlm.model."))) {
            return positive(modelBytes, "modelBytes");
        }
        if (TextModelAssetMap.TOKENIZER.equals(assetKey)
                || TextModelAssetMap.ADDED_TOKENS.equals(assetKey)
                || TextModelAssetMap.SPECIAL_TOKENS_MAP.equals(assetKey)) {
            return positive(tokenizerBytes, "tokenizerBytes");
        }
        return positive(configBytes, "configBytes");
    }

    /**
     * Resolve the per-file limit for model repository assets. This keeps file
     * classification consistent across all staging download clients.
     */
    public long maxBytesForFileName(String fileName) {
        String normalized = fileName == null
                ? ""
                : fileName.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String baseName = slash >= 0 ? normalized.substring(slash + 1) : normalized;

        if (baseName.endsWith(".gguf")
                || baseName.endsWith(".ggml")
                || baseName.endsWith(".safetensors")
                || baseName.endsWith(".onnx")
                || baseName.endsWith(".onnx_data")
                || baseName.endsWith(".tflite")
                || (baseName.endsWith(".bin")
                    && (baseName.startsWith("pytorch_model")
                        || baseName.startsWith("model-")))) {
            return maxBytesFor(TextModelAssetMap.MODEL);
        }
        if (baseName.contains("tokenizer")
                || baseName.equals("added_tokens.json")
                || baseName.equals("special_tokens_map.json")
                || baseName.equals("vocab.json")
                || baseName.equals("vocab.txt")
                || baseName.equals("merges.txt")
                || baseName.equals("spiece.model")
                || baseName.equals("sentencepiece.bpe.model")) {
            return maxBytesFor(TextModelAssetMap.TOKENIZER);
        }
        return maxBytesFor("config");
    }

    public long getModelBytes() {
        return modelBytes;
    }

    public void setModelBytes(long modelBytes) {
        this.modelBytes = modelBytes;
    }

    public long getTokenizerBytes() {
        return tokenizerBytes;
    }

    public void setTokenizerBytes(long tokenizerBytes) {
        this.tokenizerBytes = tokenizerBytes;
    }

    public long getConfigBytes() {
        return configBytes;
    }

    public void setConfigBytes(long configBytes) {
        this.configBytes = configBytes;
    }

    public long getTotalBytes() {
        return positive(totalBytes, "totalBytes");
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public int getLocalMaxFiles() {
        return positive(localMaxFiles, "localMaxFiles");
    }

    public void setLocalMaxFiles(int localMaxFiles) {
        this.localMaxFiles = localMaxFiles;
    }

    public int getLocalMaxDepth() {
        return positive(localMaxDepth, "localMaxDepth");
    }

    public void setLocalMaxDepth(int localMaxDepth) {
        this.localMaxDepth = localMaxDepth;
    }

    public int getMaxRedirects() {
        return positive(maxRedirects, "maxRedirects");
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public long getCancellationWaitMillis() {
        return positive(cancellationWaitMillis, "cancellationWaitMillis");
    }

    public void setCancellationWaitMillis(long cancellationWaitMillis) {
        this.cancellationWaitMillis = cancellationWaitMillis;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }
}
