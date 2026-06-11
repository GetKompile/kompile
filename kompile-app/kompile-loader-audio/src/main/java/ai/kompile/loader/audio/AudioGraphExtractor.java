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

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Graph extractor for audio/video documents that have been transcribed via Whisper.
 * <p>
 * Creates the following graph entities:
 * <ul>
 *   <li>{@code AUDIO_DOCUMENT} or {@code VIDEO_DOCUMENT} — the source file</li>
 *   <li>{@code TRANSCRIPT_SEGMENT} — one per timestamped segment with start/end/text</li>
 * </ul>
 * <p>
 * Relationships:
 * <ul>
 *   <li>{@code HAS_SEGMENT} — document → each segment</li>
 *   <li>{@code NEXT_SEGMENT} — sequential segment chain</li>
 * </ul>
 */
@Component
public class AudioGraphExtractor implements DocumentGraphExtractor {

    @Override
    public List<String> supportedDocumentTypes() {
        return List.of("audio", "video");
    }

    @Override
    public boolean canExtract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        // Must have audio.segments metadata (produced by AudioDocumentLoader)
        Object segments = meta.get("audio.segments");
        if (segments instanceof List && !((List<?>) segments).isEmpty()) {
            return true;
        }
        // Also accept audio/video docs that have transcription metadata
        String contentType = str(meta.get(GraphConstants.META_CONTENT_TYPE));
        return "audio".equals(contentType) || "video".equals(contentType);
    }

    @Override
    public ExtractionResult extract(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        Map<String, ExtractedEntity> entityIndex = new LinkedHashMap<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        String sourcePath = str(meta.get(GraphConstants.META_SOURCE_PATH));
        String fileName = str(meta.get(GraphConstants.META_FILE_NAME));
        String contentType = str(meta.get(GraphConstants.META_CONTENT_TYPE));
        String displayName = fileName != null ? fileName : (sourcePath != null ? sourcePath : "audio");

        // Determine entity type
        String entityType = "video".equals(contentType)
                ? GraphConstants.ENTITY_VIDEO_DOCUMENT
                : GraphConstants.ENTITY_AUDIO_DOCUMENT;

        // Create the root audio/video document entity
        String docEntityId = entityId("audio_doc:" + (sourcePath != null ? sourcePath : displayName));
        Map<String, String> docProps = new LinkedHashMap<>();
        if (sourcePath != null) docProps.put("sourcePath", sourcePath);
        if (fileName != null) docProps.put("fileName", fileName);
        putIfPresent(docProps, "duration", meta, "media.duration");
        putIfPresent(docProps, "codec", meta, "media.codec");
        putIfPresent(docProps, "sampleRate", meta, "media.sampleRate");
        putIfPresent(docProps, "channels", meta, "media.channels");
        putIfPresent(docProps, "bitRate", meta, "media.bitRate");
        putIfPresent(docProps, "trackNumber", meta, "media.trackNumber");
        putIfPresent(docProps, "discNumber", meta, "media.discNumber");
        putIfPresent(docProps, "tempo", meta, "media.tempo");
        putIfPresent(docProps, "copyright", meta, "media.copyright");
        putIfPresent(docProps, "comment", meta, "media.comment");
        // Video-specific properties
        putIfPresent(docProps, "videoFrameRate", meta, "media.videoFrameRate");
        putIfPresent(docProps, "videoWidth", meta, "media.videoWidth");
        putIfPresent(docProps, "videoHeight", meta, "media.videoHeight");
        putIfPresent(docProps, "transcriptionLanguage", meta, "audio.transcriptionLanguage");
        putIfPresent(docProps, "transcriptionModel", meta, "audio.transcriptionModel");
        putIfPresent(docProps, "segmentCount", meta, "audio.segmentCount");
        docProps.put(GraphConstants.PROP_ENTITY_SOURCE, GraphConstants.SOURCE_AUDIO_EXTRACTOR);

        addEntity(entityIndex, new ExtractedEntity(
                docEntityId, displayName, entityType,
                null, entityType + ": " + displayName, 1.0, docProps));

        // Extract transcript segments
        Object segmentsObj = meta.get("audio.segments");
        if (segmentsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> segments = (List<Map<String, String>>) segmentsObj;
            String prevSegmentId = null;

            for (int i = 0; i < segments.size() && i < 500; i++) {
                Map<String, String> seg = segments.get(i);
                String start = seg.get("start");
                String end = seg.get("end");
                String text = seg.get("text");

                if (text == null || text.isBlank()) continue;

                String segKey = sourcePath != null ? sourcePath : displayName;
                String segmentId = entityId("seg:" + segKey + ":" + i + ":" + start);
                Map<String, String> segProps = new LinkedHashMap<>();
                segProps.put("segmentIndex", String.valueOf(i));
                if (start != null) segProps.put("timestampStart", start);
                if (end != null) segProps.put("timestampEnd", end);
                segProps.put("text", text.length() > 500 ? text.substring(0, 500) : text);
                segProps.put(GraphConstants.PROP_ENTITY_SOURCE, GraphConstants.SOURCE_AUDIO_EXTRACTOR);

                String segLabel = formatTimestamp(start) + " - " + formatTimestamp(end);
                addEntity(entityIndex, new ExtractedEntity(
                        segmentId, segLabel, GraphConstants.ENTITY_TRANSCRIPT_SEGMENT,
                        null, text, 0.95, segProps));

                // HAS_SEGMENT: document → segment
                relations.add(new ExtractedRelation(
                        docEntityId, segmentId, GraphConstants.REL_HAS_SEGMENT,
                        displayName + " has transcript segment at " + segLabel,
                        0.95, Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));

                // NEXT_SEGMENT: chain sequential segments
                if (prevSegmentId != null) {
                    relations.add(new ExtractedRelation(
                            prevSegmentId, segmentId, GraphConstants.REL_NEXT_SEGMENT,
                            "Next segment in transcript",
                            0.95, Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));
                }
                prevSegmentId = segmentId;
            }
        }

        // Extract author/artist if present (reuse Tika's media metadata)
        String artist = str(meta.get("media.artist"));
        if (artist != null && !artist.isBlank()) {
            String personId = entityId("person:" + artist.toLowerCase());
            Map<String, String> personProps = new LinkedHashMap<>();
            personProps.put("name", artist);
            personProps.put(GraphConstants.PROP_SOURCE_FIELD, "artist");
            addEntity(entityIndex, new ExtractedEntity(
                    personId, artist, GraphConstants.ENTITY_PERSON,
                    null, "Artist: " + artist, 0.85, personProps));
            relations.add(new ExtractedRelation(
                    docEntityId, personId, GraphConstants.REL_AUTHORED_BY,
                    displayName + " by " + artist, 0.85,
                    Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));
        }

        // Extract album entity
        String album = str(meta.get("media.album"));
        if (album != null && !album.isBlank()) {
            String albumId = entityId("album:" + album.toLowerCase());
            Map<String, String> albumProps = new LinkedHashMap<>();
            albumProps.put("title", album);
            albumProps.put(GraphConstants.PROP_SOURCE_FIELD, "album");
            addEntity(entityIndex, new ExtractedEntity(
                    albumId, album, GraphConstants.ENTITY_ALBUM,
                    null, "Album: " + album, 0.85, albumProps));
            relations.add(new ExtractedRelation(
                    docEntityId, albumId, GraphConstants.REL_IN_ALBUM,
                    displayName + " in album " + album, 0.85,
                    Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));
        }

        // Extract genre entity
        String genre = str(meta.get("media.genre"));
        if (genre != null && !genre.isBlank()) {
            String genreId = entityId("genre:" + genre.toLowerCase());
            Map<String, String> genreProps = new LinkedHashMap<>();
            genreProps.put("name", genre);
            genreProps.put(GraphConstants.PROP_SOURCE_FIELD, "genre");
            addEntity(entityIndex, new ExtractedEntity(
                    genreId, genre, GraphConstants.ENTITY_GENRE,
                    null, "Genre: " + genre, 0.85, genreProps));
            relations.add(new ExtractedRelation(
                    docEntityId, genreId, GraphConstants.REL_IN_GENRE,
                    displayName + " genre: " + genre, 0.85,
                    Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));
        }

        // Extract composer entity
        String composer = str(meta.get("media.composer"));
        if (composer != null && !composer.isBlank()) {
            String composerId = entityId("person:" + composer.toLowerCase());
            Map<String, String> composerProps = new LinkedHashMap<>();
            composerProps.put("name", composer);
            composerProps.put(GraphConstants.PROP_SOURCE_FIELD, "composer");
            addEntity(entityIndex, new ExtractedEntity(
                    composerId, composer, GraphConstants.ENTITY_PERSON,
                    null, "Composer: " + composer, 0.85, composerProps));
            relations.add(new ExtractedRelation(
                    docEntityId, composerId, GraphConstants.REL_COMPOSED_BY,
                    displayName + " composed by " + composer, 0.85,
                    Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));
        }

        // Extract release date entity
        String releaseDate = str(meta.get("media.releaseDate"));
        if (releaseDate != null && !releaseDate.isBlank()) {
            String dateId = entityId("date:" + releaseDate.toLowerCase());
            Map<String, String> dateProps = new LinkedHashMap<>();
            dateProps.put("date", releaseDate);
            dateProps.put(GraphConstants.PROP_SOURCE_FIELD, "releaseDate");
            addEntity(entityIndex, new ExtractedEntity(
                    dateId, releaseDate, GraphConstants.ENTITY_DATE,
                    null, "Release date: " + releaseDate, 0.8, dateProps));
            relations.add(new ExtractedRelation(
                    docEntityId, dateId, GraphConstants.REL_RELEASED_ON,
                    displayName + " released on " + releaseDate, 0.8,
                    Map.of(GraphConstants.PROP_PROVENANCE, GraphConstants.PROVENANCE_EXTRACTED)));
        }

        ExtractionMetadata extractionMeta = ExtractionMetadata.forChunk(
                docEntityId, sourcePath, GraphConstants.SOURCE_AUDIO_EXTRACTOR);

        return ExtractionResult.of(
                new ArrayList<>(entityIndex.values()),
                relations,
                extractionMeta);
    }

    @Override
    public ExtractionResult extractBatch(List<Document> docs) {
        Map<String, ExtractedEntity> allEntities = new LinkedHashMap<>();
        List<ExtractedRelation> allRelations = new ArrayList<>();

        for (Document doc : docs) {
            if (canExtract(doc)) {
                ExtractionResult result = extract(doc);
                for (ExtractedEntity e : result.entities()) {
                    allEntities.putIfAbsent(e.id(), e);
                }
                allRelations.addAll(result.relations());
            }
        }

        ExtractionMetadata extractionMeta = ExtractionMetadata.forChunk(
                null, null, GraphConstants.SOURCE_AUDIO_EXTRACTOR);

        return ExtractionResult.of(new ArrayList<>(allEntities.values()), allRelations, extractionMeta);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void addEntity(Map<String, ExtractedEntity> index, ExtractedEntity entity) {
        index.putIfAbsent(entity.id(), entity);
    }

    private static String str(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void putIfPresent(Map<String, String> target, String key,
                                      Map<String, Object> source, String sourceKey) {
        String val = str(source.get(sourceKey));
        if (val != null) target.put(key, val);
    }

    private static String formatTimestamp(String seconds) {
        if (seconds == null) return "??:??";
        try {
            double sec = Double.parseDouble(seconds);
            int totalSec = (int) sec;
            int h = totalSec / 3600;
            int m = (totalSec % 3600) / 60;
            int s = totalSec % 60;
            if (h > 0) {
                return String.format("%d:%02d:%02d", h, m, s);
            }
            return String.format("%d:%02d", m, s);
        } catch (NumberFormatException e) {
            return seconds;
        }
    }
}
