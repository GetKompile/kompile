/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.local.nativeapi;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native-image substitutions that eliminate ND4J-dependent classes from the
 * C shared library image, allowing the image to be built WITHOUT nd4j-api on the
 * classpath.
 *
 * <h3>Why this works</h3>
 * <p>{@link ai.kompile.graph.reasoning.psl.HlMrfMapInference#chooseSolver} calls
 * {@code TensorHlMrfInference.isAvailable()} to decide whether to use the ND4J-backed
 * vectorized solver. Substituting {@code isAvailable()} to return {@code false} as a
 * compile-time constant lets the GraalVM static analysis constant-fold the branch and
 * remove the entire ND4J solver path from the reachable-code set, so nd4j-api types
 * are never touched at image build time.</p>
 *
 * <p>String-form {@code className} is used throughout to avoid importing ND4J types
 * (which would defeat the purpose — the import itself would pull nd4j-api into the
 * compile-time classpath of this substitution class).</p>
 *
 * <p>Similarly {@code SameDiffSgdHlMrfInference.isAvailable()} is substituted for the
 * same reason — that class also gates on a SameDiff availability check that triggers
 * ND4J class loading.</p>
 */
@SuppressWarnings("unused") // Loaded only by native-image annotation processor
final class Nd4jSubstitutions {

    /**
     * Substitution for {@code ai.kompile.graph.reasoning.psl.TensorHlMrfInference}.
     * Makes the tensor-based HL-MRF solver unconditionally unavailable in the C library,
     * so {@link ai.kompile.graph.reasoning.psl.HlMrfMapInference} falls through to the
     * pure-Java {@code ScalarHlMrfInference}.
     */
    @TargetClass(className = "ai.kompile.graph.reasoning.psl.TensorHlMrfInference")
    static final class Target_TensorHlMrfInference {

        @Substitute
        public static boolean isAvailable() {
            return false;
        }
    }

}
