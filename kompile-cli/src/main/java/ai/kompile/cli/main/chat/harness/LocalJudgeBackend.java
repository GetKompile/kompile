/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.harness;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Judge backend that runs a local SameDiff model in-process via reflection.
 * <p>
 * Uses {@code LLMModelDownloader} to download a GGUF model, imports it via
 * {@code GGMLModelImport.importModel()}, and generates text via
 * {@code GenerationPipeline.create().generate()}.
 * <p>
 * All SameDiff/DL4J classes are loaded via reflection so that {@code samediff-llm}
 * remains an optional runtime dependency — the CLI compiles and runs without it.
 * If the classes are not on the classpath, {@link #isAvailable()} returns false.
 */
public class LocalJudgeBackend implements JudgeBackend {

    private final String modelId;
    private final String quantization;
    private volatile Object pipeline;      // GenerationPipeline instance
    private volatile boolean initialized;
    private volatile String initError;

    /**
     * @param modelId      model identifier, e.g. "qwen3.5-0.8b", "phi3-mini", or a direct file path
     * @param quantization quantization type, e.g. "Q4_K_M" (default if null)
     */
    public LocalJudgeBackend(String modelId, String quantization) {
        this.modelId = modelId != null ? modelId : "qwen3.5-0.8b";
        this.quantization = quantization != null ? quantization : "Q4_K_M";
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        ensureInitialized();
        if (pipeline == null) {
            throw new IllegalStateException("Local judge model not initialized: "
                    + (initError != null ? initError : "unknown error"));
        }

        // Build a combined prompt (local models don't have system/user separation)
        String combined = systemPrompt + "\n\n" + userPrompt
                + "\n\nRespond ONLY with the JSON object:";

        // Call pipeline.generate(combined).getText() via reflection
        Method generateMethod = pipeline.getClass().getMethod("generate", String.class);
        Object result = generateMethod.invoke(pipeline, combined);
        Method getText = result.getClass().getMethod("getText");
        return (String) getText.invoke(result);
    }

    @Override
    public boolean isAvailable() {
        return checkClassesAvailable();
    }

    @Override
    public void close() {
        if (pipeline != null) {
            try {
                Method closeMethod = pipeline.getClass().getMethod("close");
                closeMethod.invoke(pipeline);
            } catch (Exception e) {
                // best-effort cleanup
            }
            pipeline = null;
            initialized = false;
        }
    }

    @Override
    public String describe() {
        return "local(" + modelId + "/" + quantization + ")";
    }

    /**
     * Check whether the SameDiff LLM classes are on the classpath.
     */
    public static boolean checkClassesAvailable() {
        try {
            Class.forName("org.eclipse.deeplearning4j.llm.generation.GenerationPipeline");
            Class.forName("org.eclipse.deeplearning4j.llm.data.LLMModelDownloader");
            Class.forName("org.nd4j.ggml.GGMLModelImport");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        initialized = true;

        try {
            File modelFile = resolveModelFile();
            Object sameDiff = importModel(modelFile);
            Object tokenizer = createTokenizer(modelFile);
            this.pipeline = createPipeline(sameDiff, tokenizer);

            System.err.println("[Judge] Local model loaded: " + modelId + " (" + quantization + ")");
        } catch (Exception e) {
            this.initError = e.getMessage();
            throw new RuntimeException("Failed to initialize local judge model: " + e.getMessage(), e);
        }
    }

    private File resolveModelFile() throws Exception {
        // If modelId is a file path, use it directly
        File direct = new File(modelId);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }

        // Otherwise, resolve via LLMModelDownloader
        Class<?> downloaderClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.data.LLMModelDownloader");
        Class<?> llmModelClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.data.LLMModelDownloader$LLMModel");
        Class<?> quantTypeClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.data.LLMModelDownloader$QuantType");

        // Resolve model enum from modelId
        Object modelEnum = resolveModelEnum(llmModelClass);

        // Resolve quantization enum
        Object quantEnum;
        try {
            quantEnum = Enum.valueOf((Class<Enum>) quantTypeClass, quantization.toUpperCase());
        } catch (IllegalArgumentException e) {
            quantEnum = Enum.valueOf((Class<Enum>) quantTypeClass, "Q4_K_M");
        }

        // LLMModelDownloader.download(model, quant)
        Method downloadMethod = downloaderClass.getMethod("download", llmModelClass, quantTypeClass);
        Object downloadResult = downloadMethod.invoke(null, modelEnum, quantEnum);

        // downloadResult.getModelFile()
        Method getModelFile = downloadResult.getClass().getMethod("getModelFile");
        return (File) getModelFile.invoke(downloadResult);
    }

    @SuppressWarnings("unchecked")
    private Object resolveModelEnum(Class<?> llmModelClass) throws Exception {
        // Try matching by name patterns: "qwen3.5-0.8b" -> QWEN35_0_8B
        String normalized = modelId.toUpperCase()
                .replace(".", "")
                .replace("-", "_");

        try {
            return Enum.valueOf((Class<Enum>) llmModelClass, normalized);
        } catch (IllegalArgumentException ignored) {
        }

        // Try fromSizeLabel
        try {
            // Extract size label from modelId (e.g. "0.8b" from "qwen3.5-0.8b")
            String[] parts = modelId.split("-");
            if (parts.length >= 2) {
                String sizeLabel = parts[parts.length - 1].toUpperCase();
                Method fromSize = llmModelClass.getMethod("fromSizeLabel", String.class);
                return fromSize.invoke(null, sizeLabel);
            }
        } catch (Exception ignored) {
        }

        // Default: try first matching enum that contains the model id
        Object[] constants = llmModelClass.getEnumConstants();
        String lower = modelId.toLowerCase();
        for (Object c : constants) {
            Method getName = c.getClass().getMethod("getName");
            String name = ((String) getName.invoke(c)).toLowerCase();
            if (name.contains(lower) || lower.contains(name.replace("-", ""))) {
                return c;
            }
        }

        throw new IllegalArgumentException(
                "Cannot resolve model '" + modelId + "' to a known LLM model. "
                        + "Use a file path, or one of: qwen3.5-0.8b, qwen3.5-2b, phi3-mini, etc.");
    }

    private Object importModel(File modelFile) throws Exception {
        // GGMLModelImport.importModel(filePath)
        Class<?> importClass = Class.forName("org.nd4j.ggml.GGMLModelImport");
        Method importMethod = importClass.getMethod("importModel", File.class);
        return importMethod.invoke(null, modelFile);
    }

    private Object createTokenizer(File modelFile) throws Exception {
        // Try to create a tokenizer from the GGUF file's metadata
        // GGMLModelImport stores vocab in the SameDiff, but we need a Tokenizer instance
        // Use the Tokenizer.fromGGUF(path) if available, else fall back to a basic tokenizer
        try {
            Class<?> tokClass = Class.forName("org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer");
            Method fromGGUF = tokClass.getMethod("fromGGUF", String.class);
            return fromGGUF.invoke(null, modelFile.getAbsolutePath());
        } catch (NoSuchMethodException e) {
            // Fall back: try fromFile
            try {
                Method fromFile = Class.forName("org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer")
                        .getMethod("fromFile", String.class);
                // Look for tokenizer.json alongside the GGUF
                File tokenizerJson = new File(modelFile.getParent(), "tokenizer.json");
                if (tokenizerJson.exists()) {
                    return fromFile.invoke(null, tokenizerJson.getAbsolutePath());
                }
            } catch (NoSuchMethodException ignored) {
            }
            throw new RuntimeException("Cannot create tokenizer for model: " + modelFile.getName()
                    + ". Ensure the GGUF file contains tokenizer metadata or place tokenizer.json alongside it.");
        }
    }

    private Object createPipeline(Object sameDiff, Object tokenizer) throws Exception {
        // GenerationPipelineConfig.builder().decoder(sd).tokenizer(tok).maxNewTokens(512).build()
        Class<?> configClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.generation.GenerationPipelineConfig");
        Class<?> sdClass = Class.forName("org.nd4j.autodiff.samediff.SameDiff");
        Class<?> tokClass = Class.forName("org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer");

        // Get builder
        Method builderMethod = configClass.getMethod("builder");
        Object builder = builderMethod.invoke(null);
        Class<?> builderClass = builder.getClass();

        // Set fields
        Method decoder = builderClass.getMethod("decoder", sdClass);
        builder = decoder.invoke(builder, sameDiff);

        Method tokenizerSetter = builderClass.getMethod("tokenizer", tokClass);
        builder = tokenizerSetter.invoke(builder, tokenizer);

        Method maxTokens = builderClass.getMethod("maxNewTokens", int.class);
        builder = maxTokens.invoke(builder, 512);

        // Build config
        Method build = builderClass.getMethod("build");
        Object config = build.invoke(builder);

        // GenerationPipeline.create(config)
        Class<?> pipelineClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.generation.GenerationPipeline");
        Method create = pipelineClass.getMethod("create", configClass);
        return create.invoke(null, config);
    }
}
