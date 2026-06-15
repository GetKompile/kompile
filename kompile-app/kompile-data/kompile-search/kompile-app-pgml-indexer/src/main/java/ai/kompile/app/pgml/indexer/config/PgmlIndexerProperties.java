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

package ai.kompile.app.pgml.indexer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@ConfigurationProperties(prefix = "kompile.indexer.pgml")
@Component
public class PgmlIndexerProperties {

    /**
     * Enable PGML Indexer Service.
     */
    private boolean enabled = false;



    /**
     * Default collection name (e.g., table name in Postgres) to use if not specified in method calls.
     */
    private String defaultCollectionName = "kompile_pgml_collection";

    /**
     * Batch size for processing documents during directory indexing.
     */
    private int batchSize = 100;


}