/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.pipelines.steps.samediff;

public final class SameDiffConstants {
    private SameDiffConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.samediff.SameDiffRunner";

    // Parameter Keys (matching names in the schema.json)
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_OUTPUT_NAMES = "outputNames";
    public static final String PARAM_DEBUG_MODE = "debugMode";
    public static final String PARAM_VERBOSE_MODE = "verboseMode";
}