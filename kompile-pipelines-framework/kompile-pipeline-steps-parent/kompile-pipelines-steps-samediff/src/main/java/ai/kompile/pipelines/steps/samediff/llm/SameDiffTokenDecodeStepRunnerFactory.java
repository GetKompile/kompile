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

package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffLLMTokenizer;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffWordPieceTokenizer;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.*;

/**
 * Factory for creating SameDiff token decode step runners.
 * This step converts generated token IDs back to text.
 */
@Slf4j
public class SameDiffTokenDecodeStepRunnerFactory implements PipelineStepRunnerFactory, StepSchemaProvider {

    public static final String STEP_TYPE_NAME = "SAMEDIFF_TOKEN_DECODE";
    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.samediff.llm.SameDiffTokenDecodeStepRunner";

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        return RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new SameDiffTokenDecodeStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(stepTypeName())
                .runnerClassName(RUNNER_FQCN)
                .description("Converts generated token IDs back to text using the tokenizer")
                .configClass(GenericStepConfig.class.getName())
                .parameters(Arrays.asList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_TOKENIZER_PATH)
                                .type(ValueType.STRING)
                                .description("Path to the tokenizer JSON file")
                                .required(true).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID)
                                .type(ValueType.INT64)
                                .description("EOS token ID for cleanup")
                                .defaultValue(2L)
                                .required(false).build()
                ))
                .inputs(Collections.singletonList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_GENERATED_TOKENS)
                                .type(ValueType.NDARRAY)
                                .description("List of generated token IDs")
                                .required(true).build()
                ))
                .outputs(Collections.singletonList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_RESPONSE_OUTPUT)
                                .type(ValueType.STRING)
                                .description("Decoded text response")
                                .required(true).build()
                ))
                .build();
    }

    @Override
    public Optional<StepSchema> getSchema(String runnerClassName) {
        return Optional.empty();
    }
}

/**
 * Step runner for decoding tokens to text.
 */
@Slf4j
class SameDiffTokenDecodeStepRunner implements PipelineStepRunner {

    private SameDiffLLMTokenizer tokenizer;
    private long eosTokenId;
    private volatile boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        if (!(stepConfig instanceof GenericStepConfig)) {
            throw new IllegalArgumentException("Configuration must be an instance of GenericStepConfig");
        }
        GenericStepConfig config = (GenericStepConfig) stepConfig;
        Data params = config.getParameters();

        String tokenizerPath = params.getString(SameDiffLLMConstants.PARAM_TOKENIZER_PATH);
        if (tokenizerPath == null || tokenizerPath.isEmpty()) {
            throw new IllegalArgumentException("Tokenizer path must be specified");
        }

        this.tokenizer = new SameDiffWordPieceTokenizer();
        this.tokenizer.initialize(tokenizerPath, null);
        log.info("Initialized tokenizer from: {}", tokenizerPath);

        this.eosTokenId = params.getInt64(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID, 2L);

        this.initialized = true;
        log.info("SameDiffTokenDecodeStepRunner initialized successfully");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("SameDiffTokenDecodeStepRunner is not initialized");
        }

        INDArray generatedTokens = input.get(SameDiffLLMConstants.KEY_GENERATED_TOKENS);
        if (generatedTokens == null) {
            throw new IllegalArgumentException("Input 'generated_tokens' is required");
        }

        // Convert INDArray to long array
        List<Long> tokenList = new ArrayList<>();
        for (int i = 0; i < generatedTokens.length(); i++) {
            long tokenId = generatedTokens.getLong(i);
            if (tokenId != eosTokenId) {
                tokenList.add(tokenId);
            }
        }

        long[] tokens = tokenList.stream().mapToLong(l -> l).toArray();
        String decodedText = tokenizer.decode(tokens, true);

        Data result = Data.empty();
        result.put(SameDiffLLMConstants.KEY_RESPONSE_OUTPUT, decodedText);

        log.debug("Decoded {} tokens to text", tokens.length);
        return result;
    }

    @Override
    public void close() throws Exception {
        // Tokenizer cleanup if needed
    }
}
