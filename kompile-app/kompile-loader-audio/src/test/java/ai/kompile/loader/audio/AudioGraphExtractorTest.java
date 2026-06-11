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
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AudioGraphExtractorTest {

    private final AudioGraphExtractor extractor = new AudioGraphExtractor();

    @Test
    void canExtractReturnsTrueForAudioWithSegments() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_CONTENT_TYPE, "audio");
        meta.put("audio.segments", List.of(Map.of("start", "0.00", "end", "5.00", "text", "Hello")));
        Document doc = new Document("Hello", meta);
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForVideoContentType() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_CONTENT_TYPE, "video");
        Document doc = new Document("Some content", meta);
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForPdf() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_CONTENT_TYPE, "pdf");
        Document doc = new Document("Some content", meta);
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void extractCreatesDocumentAndSegmentEntities() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_SOURCE_PATH, "/tmp/test.wav");
        meta.put(GraphConstants.META_FILE_NAME, "test.wav");
        meta.put(GraphConstants.META_CONTENT_TYPE, "audio");
        meta.put("audio.transcriptionLanguage", "en");
        meta.put("audio.transcriptionModel", "whisper");
        meta.put("audio.segmentCount", "3");

        List<Map<String, String>> segments = new ArrayList<>();
        segments.add(Map.of("start", "0.00", "end", "3.50", "text", "Hello world"));
        segments.add(Map.of("start", "3.50", "end", "7.20", "text", "How are you"));
        segments.add(Map.of("start", "7.20", "end", "10.00", "text", "Goodbye"));
        meta.put("audio.segments", segments);

        Document doc = new Document("Hello world How are you Goodbye", meta);
        ExtractionResult result = extractor.extract(doc);

        assertNotNull(result);
        // 1 audio doc entity + 3 segment entities = 4
        assertEquals(4, result.entities().size());

        // Check entity types
        long audioDocCount = result.entities().stream()
                .filter(e -> GraphConstants.ENTITY_AUDIO_DOCUMENT.equals(e.type()))
                .count();
        assertEquals(1, audioDocCount);

        long segmentCount = result.entities().stream()
                .filter(e -> GraphConstants.ENTITY_TRANSCRIPT_SEGMENT.equals(e.type()))
                .count();
        assertEquals(3, segmentCount);

        // Check relationships: 3 HAS_SEGMENT + 2 NEXT_SEGMENT = 5
        long hasSegment = result.relations().stream()
                .filter(r -> GraphConstants.REL_HAS_SEGMENT.equals(r.type()))
                .count();
        assertEquals(3, hasSegment);

        long nextSegment = result.relations().stream()
                .filter(r -> GraphConstants.REL_NEXT_SEGMENT.equals(r.type()))
                .count();
        assertEquals(2, nextSegment);

        assertEquals(5, result.relations().size());
    }

    @Test
    void extractCreatesVideoDocumentEntityForVideo() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_SOURCE_PATH, "/tmp/test.mp4");
        meta.put(GraphConstants.META_FILE_NAME, "test.mp4");
        meta.put(GraphConstants.META_CONTENT_TYPE, "video");

        List<Map<String, String>> segments = new ArrayList<>();
        segments.add(Map.of("start", "0.00", "end", "5.00", "text", "Scene one"));
        meta.put("audio.segments", segments);

        Document doc = new Document("Scene one", meta);
        ExtractionResult result = extractor.extract(doc);

        long videoCount = result.entities().stream()
                .filter(e -> GraphConstants.ENTITY_VIDEO_DOCUMENT.equals(e.type()))
                .count();
        assertEquals(1, videoCount);
    }

    @Test
    void extractCreatesArtistEntity() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_SOURCE_PATH, "/tmp/song.mp3");
        meta.put(GraphConstants.META_FILE_NAME, "song.mp3");
        meta.put(GraphConstants.META_CONTENT_TYPE, "audio");
        meta.put("media.artist", "John Doe");

        Document doc = new Document("Lyrics here", meta);
        ExtractionResult result = extractor.extract(doc);

        long personCount = result.entities().stream()
                .filter(e -> GraphConstants.ENTITY_PERSON.equals(e.type()))
                .count();
        assertEquals(1, personCount);

        long authoredBy = result.relations().stream()
                .filter(r -> GraphConstants.REL_AUTHORED_BY.equals(r.type()))
                .count();
        assertEquals(1, authoredBy);
    }

    @Test
    void extractBatchDeduplicatesEntities() {
        Map<String, Object> meta1 = new LinkedHashMap<>();
        meta1.put(GraphConstants.META_SOURCE_PATH, "/tmp/audio1.wav");
        meta1.put(GraphConstants.META_FILE_NAME, "audio1.wav");
        meta1.put(GraphConstants.META_CONTENT_TYPE, "audio");
        meta1.put("audio.segments", List.of(
                Map.of("start", "0.00", "end", "2.00", "text", "First")));

        Map<String, Object> meta2 = new LinkedHashMap<>();
        meta2.put(GraphConstants.META_SOURCE_PATH, "/tmp/audio2.wav");
        meta2.put(GraphConstants.META_FILE_NAME, "audio2.wav");
        meta2.put(GraphConstants.META_CONTENT_TYPE, "audio");
        meta2.put("audio.segments", List.of(
                Map.of("start", "0.00", "end", "3.00", "text", "Second")));

        Document doc1 = new Document("First", meta1);
        Document doc2 = new Document("Second", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));
        // 2 audio docs + 2 segments = 4
        assertEquals(4, result.entities().size());
    }

    @Test
    void segmentEntityHasTimestampProperties() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(GraphConstants.META_SOURCE_PATH, "/tmp/test.wav");
        meta.put(GraphConstants.META_FILE_NAME, "test.wav");
        meta.put(GraphConstants.META_CONTENT_TYPE, "audio");
        meta.put("audio.segments", List.of(
                Map.of("start", "12.50", "end", "18.30", "text", "Some speech")));

        Document doc = new Document("Some speech", meta);
        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity segment = result.entities().stream()
                .filter(e -> GraphConstants.ENTITY_TRANSCRIPT_SEGMENT.equals(e.type()))
                .findFirst().orElseThrow();

        assertEquals("12.50", segment.properties().get("timestampStart"));
        assertEquals("18.30", segment.properties().get("timestampEnd"));
        assertEquals("Some speech", segment.properties().get("text"));
        assertEquals("0", segment.properties().get("segmentIndex"));
    }
}
