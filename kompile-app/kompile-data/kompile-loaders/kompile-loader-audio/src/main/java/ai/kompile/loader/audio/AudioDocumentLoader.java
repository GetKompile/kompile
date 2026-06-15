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

package ai.kompile.loader.audio;

import ai.kompile.core.graphrag.GraphConstants;
import org.eclipse.deeplearning4j.audio.whisper.WhisperDecoderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads audio files and produces Spring AI {@link Document}s with transcript text
 * and segment metadata for graph extraction.
 * <p>
 * Each audio file produces one Document whose text is the full transcript.
 * Transcript segments with timestamps are stored in metadata under
 * {@code "audio.segments"} as a serialized list of maps with keys:
 * {@code start}, {@code end}, {@code text}.
 */
public class AudioDocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(AudioDocumentLoader.class);

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "wav", "mp3", "flac", "ogg", "m4a", "aac", "wma", "opus", "webm"
    );

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "m4v", "webm"
    );

    private final WhisperTranscriptionService transcriptionService;

    public AudioDocumentLoader(WhisperTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    /**
     * Check if a file is a supported audio file based on extension.
     */
    public static boolean isAudioFile(File file) {
        String ext = getExtension(file.getName());
        return AUDIO_EXTENSIONS.contains(ext);
    }

    /**
     * Check if a file is a supported video file based on extension.
     */
    public static boolean isVideoFile(File file) {
        String ext = getExtension(file.getName());
        return VIDEO_EXTENSIONS.contains(ext);
    }

    /**
     * Check if a file is a supported audio or video file.
     */
    public static boolean isAudioOrVideoFile(File file) {
        return isAudioFile(file) || isVideoFile(file);
    }

    /**
     * Load an audio file and transcribe it, producing a Document with
     * full transcript text and segment metadata.
     *
     * @param audioFile the audio file
     * @param language  language code or null for auto-detect
     * @return a Document with transcript text and segment metadata
     */
    public Document loadAudioDocument(File audioFile, String language) throws IOException {
        WhisperDecoderResult result = transcriptionService.transcribe(audioFile, language);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GraphConstants.META_SOURCE_PATH, audioFile.getAbsolutePath());
        metadata.put(GraphConstants.META_FILE_NAME, audioFile.getName());
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, isVideoFile(audioFile) ? "Video" : "Audio");
        metadata.put(GraphConstants.META_CONTENT_TYPE, isVideoFile(audioFile) ? "video" : "audio");
        metadata.put("audio.transcriptionLanguage", result.getLanguage());
        metadata.put("audio.transcriptionModel", "whisper");

        // Store segments as list of maps
        if (result.getSegments() != null && !result.getSegments().isEmpty()) {
            List<Map<String, String>> segments = new ArrayList<>();
            for (WhisperDecoderResult.Segment seg : result.getSegments()) {
                Map<String, String> segMap = new LinkedHashMap<>();
                segMap.put("start", String.format("%.2f", seg.getStart()));
                segMap.put("end", String.format("%.2f", seg.getEnd()));
                segMap.put("text", seg.getText());
                segments.add(segMap);
            }
            metadata.put("audio.segments", segments);
            metadata.put("audio.segmentCount", String.valueOf(segments.size()));

            // Calculate total duration from last segment end
            double lastEnd = result.getSegments().get(result.getSegments().size() - 1).getEnd();
            metadata.put("media.duration", String.format("%.2f", lastEnd));
        }

        String text = result.getText();
        if (text == null || text.isBlank()) {
            text = "[No speech detected]";
        }

        return new Document(text, metadata);
    }

    /**
     * Load an audio file with default language (auto-detect).
     */
    public Document loadAudioDocument(File audioFile) throws IOException {
        return loadAudioDocument(audioFile, null);
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
