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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.subprocess.model;

import io.anserini.encoder.samediff.SameDiffEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInitSubprocessMainTest {

    @AfterEach
    void restoreDefault() {
        SameDiffEncoder.setValidateOnInit(true);
    }

    @Test
    void prewarmSkipValidationIsAppliedBeforeEncoderConstruction() {
        ModelInitSubprocessMain.configureEncoderValidation(
                ModelInitSubprocessArgs.builder().skipValidation(true).build());

        assertFalse(SameDiffEncoder.isValidateOnInit());
    }

    @Test
    void normalModelInitKeepsConstructorValidationEnabled() {
        SameDiffEncoder.setValidateOnInit(false);

        ModelInitSubprocessMain.configureEncoderValidation(
                ModelInitSubprocessArgs.builder().skipValidation(false).build());

        assertTrue(SameDiffEncoder.isValidateOnInit());
    }
}
