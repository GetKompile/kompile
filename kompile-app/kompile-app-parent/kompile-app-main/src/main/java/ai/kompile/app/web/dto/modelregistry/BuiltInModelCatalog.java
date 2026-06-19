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
package ai.kompile.app.web.dto.modelregistry;

import java.util.List;
import java.util.Map;

/**
 * All built-in models grouped by RAG pipeline phase
 */
public class BuiltInModelCatalog {
    public List<BuiltInModelInfo> denseEncoders;
    public List<BuiltInModelInfo> sparseEncoders;
    public List<BuiltInModelInfo> crossEncoders;
    public Map<String, Integer> counts;

    public BuiltInModelCatalog(List<BuiltInModelInfo> denseEncoders, List<BuiltInModelInfo> sparseEncoders,
                               List<BuiltInModelInfo> crossEncoders, Map<String, Integer> counts) {
        this.denseEncoders = denseEncoders;
        this.sparseEncoders = sparseEncoders;
        this.crossEncoders = crossEncoders;
        this.counts = counts;
    }
}
