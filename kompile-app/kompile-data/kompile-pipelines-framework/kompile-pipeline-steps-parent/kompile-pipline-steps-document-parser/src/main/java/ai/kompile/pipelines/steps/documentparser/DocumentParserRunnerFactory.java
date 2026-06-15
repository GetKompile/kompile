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

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipline-steps-document-parser/src/main/java/ai/kompile/pipelines/steps/documentparser/
package ai.kompile.pipelines.steps.documentparser;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory; // Updated interface
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig; // For configClass

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DocumentParserRunnerFactory implements PipelineStepRunnerFactory {

    // Assume DocumentParserConstants.STEP_TYPE_NAME exists, or define it here
    public static final String STEP_TYPE_NAME =  "DOCUMENT_PARSER"; // Or "DOCUMENT_PARSER"

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        return DocumentParserConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new DocumentParserRunner();
    }

    @Override
    public StepSchema getSchema() {
        List<ParameterSchema> params = Arrays.asList(
                ParameterSchema.builder().name("inputUri")
                        .type(ValueType.STRING)
                        .description("URI of the document to parse (e.g., file path or URL).")
                        .required(true).build(),
                ParameterSchema.builder().name("outputKey")
                        .type(ValueType.STRING)
                        .description("Key under which the parsed document content will be stored in the output Data object.")
                        .defaultValue("parsed_content")
                        .required(false).build(),
                ParameterSchema.builder().name("extractionType")
                        .type(ValueType.STRING)
                        .description("Type of text extraction to perform (e.g., OCR_ONLY, TEXT_ONLY, AUTO).")
                        .defaultValue(TextExtractionType.AUTO.name()) // Assuming TextExtractionType enum exists
                        .allowedValues(Arrays.asList(TextExtractionType.AUTO.name(), TextExtractionType.OCR_ONLY.name(), TextExtractionType.PLAIN_TEXT.name()))
                        .required(false).build(),
                ParameterSchema.builder().name("ocrLanguage")
                        .type(ValueType.STRING)
                        .description("Language(s) for OCR, e.g., 'eng', 'jpn+eng'.")
                        .defaultValue("eng")
                        .required(false).build()
        );

        List<ParameterSchema> inputs = Collections.singletonList(
                ParameterSchema.builder().name("document_uri_input") // Example input name if URI is from Data
                        .type(ValueType.STRING)
                        .description("Optional input Data key providing the document URI, overrides 'inputUri' parameter if present.")
                        .required(false).build()
        );

        List<ParameterSchema> outputs = Collections.singletonList(
                ParameterSchema.builder().name("parsed_content") // Matches default outputKey
                        .type(ValueType.STRING)
                        .description("The extracted text content from the document.")
                        .required(true).build()
        );

        return StepSchema.builder()
                .name(stepTypeName())
                .runnerClassName(DocumentParserConstants.RUNNER_FQCN)
                .description("Parses various document formats (PDF, DOCX, TXT, images with OCR) to extract text content.")
                .configClass(GenericStepConfig.class.getName()) // Assuming DocumentParserRunner uses GenericStepConfig
                .parameters(params)
                .inputs(inputs)
                .outputs(outputs)
                .build();
    }
}