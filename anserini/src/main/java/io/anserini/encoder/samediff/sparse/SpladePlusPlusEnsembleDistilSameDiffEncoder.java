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

package io.anserini.encoder.samediff.sparse;

import java.io.IOException;
import java.net.URISyntaxException;

public class SpladePlusPlusEnsembleDistilSameDiffEncoder extends SpladePlusPlusSameDiffEncoder {
    // IMPORTANT: Update URLs to point to your converted SameDiff models (.sd files)
    public static final String DEFAULT_MODEL_NAME = "splade-pp-ed-optimized.sd";
    public static final String DEFAULT_MODEL_URL = "YOUR_MODEL_REPO_URL/splade-pp-ed-optimized.sd"; // Replace
    public static final String DEFAULT_VOCAB_NAME = "splade-pp-ed-vocab.txt"; // Or standard bert vocab
    public static final String DEFAULT_VOCAB_URL = "https://rgw.cs.uwaterloo.ca/pyserini/data/wordpiece-vocab.txt"; // From original

    public SpladePlusPlusEnsembleDistilSameDiffEncoder() throws IOException, URISyntaxException {
        super(DEFAULT_MODEL_NAME, DEFAULT_MODEL_URL, DEFAULT_VOCAB_NAME, DEFAULT_VOCAB_URL);
    }
}