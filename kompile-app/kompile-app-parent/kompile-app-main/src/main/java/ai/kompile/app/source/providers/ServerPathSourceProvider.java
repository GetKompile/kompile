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
 * Source provider for server-side file paths.
 * Allows indexing files and directories accessible from the server.
 */
@Component
public class ServerPathSourceProvider implements SourceProvider {

    @Override
    public String getId() {
        return "path";
    }

    @Override
    public String getDisplayName() {
        return "Server Path";
    }

    @Override
    public String getDescription() {
        return "Index files from a path on the server";
    }

    @Override
    public String getIcon() {
        return "folder";
    }

    @Override
    public String getCategory() {
        return "local";
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("path")
                        .label("Path")
                        .type(SourceFormField.FieldType.TEXT)
                        .required(true)
                        .placeholder("/path/to/documents")
                        .helpText("Absolute path to a file or directory on the server")
                        .prefixIcon("folder")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("recursive")
                        .label("Include subdirectories")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Recursively process files in subdirectories")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("filePattern")
                        .label("File Pattern")
                        .type(SourceFormField.FieldType.TEXT)
                        .placeholder("*.pdf,*.docx")
                        .helpText("Optional: Filter by file extension (comma-separated)")
                        .order(3)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("loader")
                        .label("Document Loader")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("Auto-detect usually works best")
                        .order(4)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split documents into chunks")
                        .order(5)
                        .group("advanced")
                        .build()
        );
    }
}
