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

/**
 * Source provider for URL/web page ingestion.
 */
@Component
public class UrlSourceProvider implements SourceProvider {

    @Override
    public String getId() {
        return "url";
    }

    @Override
    public String getDisplayName() {
        return "Add URL";
    }

    @Override
    public String getDescription() {
        return "Import content from a web URL";
    }

    @Override
    public String getIcon() {
        return "link";
    }

    @Override
    public String getCategory() {
        return "web";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("url")
                        .label("URL")
                        .type(SourceFormField.FieldType.URL)
                        .required(true)
                        .placeholder("https://example.com/document.pdf")
                        .pattern("^(https?|ftp)://[^\\s/$.?#].[^\\s]*$")
                        .patternError("Please enter a valid URL")
                        .prefixIcon("link")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("fileName")
                        .label("Display Name")
                        .type(SourceFormField.FieldType.TEXT)
                        .placeholder("Optional: Name for this source")
                        .helpText("A friendly name to identify this source")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("loader")
                        .label("Document Loader")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("Auto-detect usually works best")
                        .order(3)
                        .group("advanced")
                        .build()
        );
    }
}
