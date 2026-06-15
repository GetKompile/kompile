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

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Factory for creating SameDiff tokenizer step runners.
 * This step tokenizes input text into token IDs.
 */
@Slf4j
public class SameDiffLLMTokenizerStepRunnerFactory implements PipelineStepRunnerFactory, StepSchemaProvider {

    public static final String STEP_TYPE_NAME = "SAMEDIFF_LLM_TOKENIZER";
    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.samediff.llm.SameDiffLLMTokenizerStepRunner";

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
        return new SameDiffLLMTokenizerStepRunner();
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(stepTypeName())
                .runnerClassName(RUNNER_FQCN)
                .description("Tokenizes input text into token IDs using SameDiff-compatible tokenizer")
                .configClass(GenericStepConfig.class.getName())
                .parameters(Arrays.asList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_TOKENIZER_PATH)
                                .type(ValueType.STRING)
                                .description("Path to the tokenizer JSON file")
                                .required(true).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_TOKENIZER_TYPE)
                                .type(ValueType.STRING)
                                .description("Tokenizer type (samediff_wordpiece, wordpiece, bpe, unigram)")
                                .defaultValue("samediff_wordpiece")
                                .required(false).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_EOS_TOKEN_ID)
                                .type(ValueType.INT64)
                                .description("EOS token ID")
                                .defaultValue(2L)
                                .required(false).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_PAD_TOKEN_ID)
                                .type(ValueType.INT64)
                                .description("PAD token ID")
                                .defaultValue(0L)
                                .required(false).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.PARAM_UNK_TOKEN_ID)
                                .type(ValueType.INT64)
                                .description("UNK token ID")
                                .defaultValue(1L)
                                .required(false).build()
                ))
                .inputs(Collections.singletonList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_PROMPT_INPUT)
                                .type(ValueType.STRING)
                                .description("Input text to tokenize")
                                .required(true).build()
                ))
                .outputs(Arrays.asList(
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_INPUT_IDS)
                                .type(ValueType.NDARRAY)
                                .description("Token IDs as INDArray")
                                .required(true).build(),
                        ParameterSchema.builder().name(SameDiffLLMConstants.KEY_ATTENTION_MASK)
                                .type(ValueType.NDARRAY)
                                .description("Attention mask (1 for real tokens, 0 for padding)")
                                .required(false).build()
                ))
                .build();
    }

    @Override
    public Optional<StepSchema> getSchema(String runnerClassName) {
        return Optional.empty();
    }
}
