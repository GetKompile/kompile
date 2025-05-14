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

package ai.kompile.anserini.config; // New package for this module's config

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component // Makes it a bean, discoverable by the main app if this module is on classpath
@ConfigurationProperties(prefix = "anserini")
public class AnseriniConfig {
    /**
     * Path where the final Anserini/Lucene index will be built and stored.
     */
    private String indexPath;

    /**
     * Path used by AnseriniIndexerServiceImpl as the staging directory
     * for intermediate JSON files that Anserini's JsonCollection will ingest.
     */
    private String corpusPath; // This was used as the staging path for JSONs
}