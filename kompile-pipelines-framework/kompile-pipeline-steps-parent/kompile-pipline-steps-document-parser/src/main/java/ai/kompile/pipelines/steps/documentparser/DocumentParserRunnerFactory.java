/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.steps.documentparser;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;

public class DocumentParserRunnerFactory implements PipelineStepRunnerFactory {
    @Override
    public String getRunnerType() {
        // This FQCN must match what users put in their StepConfig's "runnerClassName"
        return DocumentParserConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new DocumentParserRunner();
    }
}