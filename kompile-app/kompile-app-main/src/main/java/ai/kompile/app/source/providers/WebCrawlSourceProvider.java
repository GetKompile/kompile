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
 * Source provider for web crawling. Exposes crawler configuration
 * in the document sources UI so users can start recursive web crawls.
 */
@Component
public class WebCrawlSourceProvider implements SourceProvider {

    @Override
    public String getId() {
        return "web-crawl";
    }

    @Override
    public String getDisplayName() {
        return "Crawl Website";
    }

    @Override
    public String getDescription() {
        return "Recursively crawl a website following links to discover and index all pages";
    }

    @Override
    public String getIcon() {
        return "travel_explore";
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
    public Map<String, Object> getConfiguration() {
        return Map.of(
                "crawlerId", "web",
                "supportsIncrementalCrawl", true
        );
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("seed")
                        .label("Starting URL")
                        .type(SourceFormField.FieldType.URL)
                        .required(true)
                        .placeholder("https://docs.example.com")
                        .helpText("The crawler will start from this URL and follow links")
                        .pattern("^https?://[^\\s/$.?#].[^\\s]*$")
                        .patternError("Please enter a valid HTTP/HTTPS URL")
                        .prefixIcon("travel_explore")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("maxDepth")
                        .label("Maximum Depth")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(3)
                        .min(1)
                        .max(10)
                        .helpText("How many links deep to follow from the starting URL")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("maxDocuments")
                        .label("Maximum Pages")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(1000)
                        .min(1)
                        .max(100000)
                        .helpText("Maximum number of pages to crawl (0 = unlimited)")
                        .order(3)
                        .build(),
                SourceFormField.builder()
                        .id("sameDomainOnly")
                        .label("Same Domain Only")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Only follow links within the same domain as the starting URL")
                        .order(4)
                        .build(),
                SourceFormField.builder()
                        .id("respectRobotsTxt")
                        .label("Respect robots.txt")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Honor robots.txt directives from the website")
                        .order(5)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("requestDelay")
                        .label("Request Delay (ms)")
                        .type(SourceFormField.FieldType.NUMBER)
                        .defaultValue(500)
                        .min(0)
                        .max(10000)
                        .step(100)
                        .helpText("Milliseconds to wait between requests to avoid overloading the server")
                        .order(6)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("includePatterns")
                        .label("Include URL Patterns")
                        .type(SourceFormField.FieldType.TEXTAREA)
                        .placeholder(".*\\.html$\n.*/docs/.*")
                        .helpText("Regex patterns (one per line). Only URLs matching at least one pattern are crawled. Leave empty to include all.")
                        .order(7)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("excludePatterns")
                        .label("Exclude URL Patterns")
                        .type(SourceFormField.FieldType.TEXTAREA)
                        .placeholder(".*\\.(css|js|png|jpg|gif)$\n.*/login.*")
                        .helpText("Regex patterns (one per line). URLs matching any pattern are skipped.")
                        .order(8)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("collectionName")
                        .label("Collection Name")
                        .type(SourceFormField.FieldType.TEXT)
                        .placeholder("Optional: target index collection")
                        .order(9)
                        .group("advanced")
                        .build()
        );
    }
}
