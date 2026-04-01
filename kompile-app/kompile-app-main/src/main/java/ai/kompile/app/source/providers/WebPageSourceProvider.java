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

package ai.kompile.app.source.providers;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Source provider for web page crawling/scraping.
 * Allows adding multiple URLs for bulk web content ingestion.
 */
@Component
public class WebPageSourceProvider implements SourceProvider {

    @Override
    public String getId() {
        return "web";
    }

    @Override
    public String getDisplayName() {
        return "Add Web Pages";
    }

    @Override
    public String getDescription() {
        return "Crawl and index web pages and websites";
    }

    @Override
    public String getIcon() {
        return "language";
    }

    @Override
    public String getCategory() {
        return "web";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public boolean hasCustomDialog() {
        return true;
    }

    @Override
    public String getCustomDialogComponent() {
        return "WebSourceDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("urls")
                        .label("URLs")
                        .type(SourceFormField.FieldType.TEXTAREA)
                        .required(true)
                        .placeholder("Enter one URL per line")
                        .helpText("Add multiple URLs, one per line")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("crawlDepth")
                        .label("Crawl Depth")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(0)
                        .min(0)
                        .max(5)
                        .helpText("How many links deep to follow (0 = single page only)")
                        .order(2)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("followLinks")
                        .label("Follow Links")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(false)
                        .helpText("Follow links on the page to crawl more content")
                        .order(3)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split content into chunks")
                        .order(4)
                        .group("advanced")
                        .build()
        );
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return Map.of(
                "maxUrlsPerBatch", 50,
                "defaultCrawlDepth", 0
        );
    }
}
