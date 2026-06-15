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
 * Source provider for YouTube video transcripts.
 */
@Component
public class YouTubeSourceProvider implements SourceProvider {

    private static final List<SourceFormField.SelectOption> LANGUAGE_OPTIONS = Arrays.asList(
            new SourceFormField.SelectOption("en", "English", null, false),
            new SourceFormField.SelectOption("es", "Spanish", null, false),
            new SourceFormField.SelectOption("fr", "French", null, false),
            new SourceFormField.SelectOption("de", "German", null, false),
            new SourceFormField.SelectOption("it", "Italian", null, false),
            new SourceFormField.SelectOption("pt", "Portuguese", null, false),
            new SourceFormField.SelectOption("ru", "Russian", null, false),
            new SourceFormField.SelectOption("ja", "Japanese", null, false),
            new SourceFormField.SelectOption("ko", "Korean", null, false),
            new SourceFormField.SelectOption("zh", "Chinese", null, false),
            new SourceFormField.SelectOption("ar", "Arabic", null, false),
            new SourceFormField.SelectOption("hi", "Hindi", null, false)
    );

    @Override
    public String getId() {
        return "youtube";
    }

    @Override
    public String getDisplayName() {
        return "YouTube";
    }

    @Override
    public String getDescription() {
        return "Import transcripts from YouTube videos";
    }

    @Override
    public String getIcon() {
        return "smart_display";
    }

    @Override
    public String getCategory() {
        return "web";
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        return Arrays.asList(
                SourceFormField.builder()
                        .id("youtubeUrl")
                        .label("YouTube URL")
                        .type(SourceFormField.FieldType.URL)
                        .required(true)
                        .placeholder("https://www.youtube.com/watch?v=...")
                        .pattern("^(https?://)?(www\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)[a-zA-Z0-9_-]{11}")
                        .patternError("Please enter a valid YouTube URL")
                        .prefixIcon("smart_display")
                        .order(1)
                        .build(),
                SourceFormField.builder()
                        .id("language")
                        .label("Transcript Language")
                        .type(SourceFormField.FieldType.SELECT)
                        .defaultValue("en")
                        .options(LANGUAGE_OPTIONS)
                        .helpText("Preferred language for the transcript")
                        .order(2)
                        .build(),
                SourceFormField.builder()
                        .id("saveTranscriptFile")
                        .label("Save Transcript File")
                        .type(SourceFormField.FieldType.TOGGLE)
                        .defaultValue(true)
                        .helpText("Save the transcript as a text file")
                        .order(3)
                        .build(),
                SourceFormField.builder()
                        .id("chunkerName")
                        .label("Chunking Strategy")
                        .type(SourceFormField.FieldType.SELECT)
                        .helpText("How to split the transcript into chunks")
                        .order(4)
                        .group("advanced")
                        .build()
        );
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return Map.of(
                "supportedLanguages", LANGUAGE_OPTIONS
        );
    }
}
