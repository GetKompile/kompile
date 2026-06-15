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
 * Source provider for file uploads.
 * This is a core provider that's always available.
 */
@Component
public class FileUploadSourceProvider implements SourceProvider {

    private static final String ACCEPTED_FILE_TYPES =
            ".pdf,.doc,.docx,.txt,.html,.htm,.xml,.json,.csv,.xls,.xlsx," +
            ".ppt,.pptx,.md,.rtf,.odt,.ods,.odp,.epub,.eml,.msg";

    @Override
    public String getId() {
        return "file";
    }

    @Override
    public String getDisplayName() {
        return "Upload Files";
    }

    @Override
    public String getDescription() {
        return "Upload documents from your computer to index";
    }

    @Override
    public String getIcon() {
        return "upload_file";
    }

    @Override
    public String getCategory() {
        return "local";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public boolean hasCustomDialog() {
        // Use custom dialog for drag-drop and advanced options
        return true;
    }

    @Override
    public String getCustomDialogComponent() {
        return "AddSourceDialogComponent";
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("files")
                        .label("Select Files")
                        .type(SourceFormField.FieldType.FILE)
                        .accept(ACCEPTED_FILE_TYPES)
                        .multiple(true)
                        .required(true)
                        .helpText("Drag and drop files or click to browse")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("loader")
                        .label("Document Loader")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("Auto-detect usually works best")
                        .order(2)
                        .group("advanced")
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split documents into chunks")
                        .order(3)
                        .group("advanced")
                        .build()
        );
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return Map.of(
                "acceptedFileTypes", ACCEPTED_FILE_TYPES,
                "maxFileSize", 100 * 1024 * 1024, // 100MB
                "dragDropEnabled", true
        );
    }
}
