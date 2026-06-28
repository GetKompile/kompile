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
package ai.kompile.graph.reasoning.prior;

/**
 * No-op {@link PriorProvider}: always returns {@code 0.5} (maximum uncertainty).
 *
 * <p>This is the safe default for plain-Java and test contexts that have no opinion store,
 * embeddings, or temporal metadata.  It preserves the previous hard-coded behaviour exactly.</p>
 */
public final class DefaultPriorProvider implements PriorProvider {

    /** Singleton — this class carries no state. */
    public static final PriorProvider INSTANCE = new DefaultPriorProvider();

    private DefaultPriorProvider() {}

    @Override
    public double priorFor(String rvKey, PriorContext ctx) {
        return 0.5;
    }

    @Override
    public double strengthFor(String parentRv, String childRv, PriorContext ctx) {
        return 0.5;
    }
}
