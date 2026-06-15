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
 * Source provider for pasting text content directly.
 */
@Component
public class TextSourceProvider implements SourceProvider {

    @Override
    public String getId() {
        return "text";
    }

    @Override
    public String getDisplayName() {
        return "Paste Text";
    }

    @Override
    public String getDescription() {
        return "Paste or type text content directly";
    }

    @Override
    public String getIcon() {
        return "article";
    }

    @Override
    public String getCategory() {
        return "local";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public boolean hasCustomDialog() {
        return true;
    }

    @Override
    public String getCustomDialogComponent() {
        return "TextSourceDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("title")
                        .label("Title")
                        .type(SourceFormField.FieldType.TEXT)
                        .placeholder("Document title")
                        .helpText("A name for this text document")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("content")
                        .label("Content")
                        .type(SourceFormField.FieldType.TEXTAREA)
                        .required(true)
                        .placeholder("Paste or type your text content here...")
                        .helpText("The text content to index")
                        .attributes(Map.of("rows", 15))
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split the text into chunks")
                        .order(3)
                        .group("advanced")
                        .build()
        );
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return Map.of(
                "maxContentLength", 1000000 // 1MB of text
        );
    }
}
