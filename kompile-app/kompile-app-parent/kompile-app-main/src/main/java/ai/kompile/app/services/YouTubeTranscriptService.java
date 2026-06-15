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

package ai.kompile.app.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for fetching YouTube video transcripts/captions.
 * Uses YouTube's public caption endpoints that don't require API keys.
 */
@Service
public class YouTubeTranscriptService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeTranscriptService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Patterns to extract video ID from various YouTube URL formats
    private static final Pattern[] VIDEO_ID_PATTERNS = {
        Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/v/)([a-zA-Z0-9_-]{11})"),
        Pattern.compile("^([a-zA-Z0-9_-]{11})$") // Direct video ID
    };

    // Pattern to extract caption tracks from YouTube page
    private static final Pattern CAPTION_TRACKS_PATTERN = Pattern.compile("\"captionTracks\":\\s*\\[(.*?)\\]");
    private static final Pattern BASE_URL_PATTERN = Pattern.compile("\"baseUrl\":\\s*\"([^\"]+)\"");
    private static final Pattern LANG_CODE_PATTERN = Pattern.compile("\"languageCode\":\\s*\"([^\"]+)\"");

    public YouTubeTranscriptService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Result object containing transcript data and metadata
     */
    public static class TranscriptResult {
        private final String videoId;
        private final String title;
        private final String transcript;
        private final String language;
        private final List<TranscriptSegment> segments;
        private final Map<String, Object> metadata;

        public TranscriptResult(String videoId, String title, String transcript,
                               String language, List<TranscriptSegment> segments,
                               Map<String, Object> metadata) {
            this.videoId = videoId;
            this.title = title;
            this.transcript = transcript;
            this.language = language;
            this.segments = segments;
            this.metadata = metadata;
        }

        public String getVideoId() { return videoId; }
        public String getTitle() { return title; }
        public String getTranscript() { return transcript; }
        public String getLanguage() { return language; }
        public List<TranscriptSegment> getSegments() { return segments; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    /**
     * Represents a single transcript segment with timing
     */
    public static class TranscriptSegment {
        private final String text;
        private final double startTime;
        private final double duration;

        public TranscriptSegment(String text, double startTime, double duration) {
            this.text = text;
            this.startTime = startTime;
            this.duration = duration;
        }

        public String getText() { return text; }
        public double getStartTime() { return startTime; }
        public double getDuration() { return duration; }
    }

    /**
     * Extract video ID from various YouTube URL formats.
     */
    public String extractVideoId(String urlOrId) {
        if (urlOrId == null || urlOrId.trim().isEmpty()) {
            return null;
        }

        String input = urlOrId.trim();

        for (Pattern pattern : VIDEO_ID_PATTERNS) {
            Matcher matcher = pattern.matcher(input);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        // Try to extract from query parameter
        if (input.contains("v=")) {
            int vIndex = input.indexOf("v=");
            String afterV = input.substring(vIndex + 2);
            int endIndex = afterV.indexOf('&');
            if (endIndex == -1) {
                endIndex = afterV.length();
            }
            String videoId = afterV.substring(0, Math.min(11, endIndex));
            if (videoId.length() == 11) {
                return videoId;
            }
        }

        return null;
    }

    /**
     * Fetch transcript for a YouTube video.
     *
     * @param urlOrVideoId YouTube URL or video ID
     * @param preferredLanguage Preferred language code (e.g., "en", "es"). If null, uses first available.
     * @return TranscriptResult containing the transcript and metadata
     * @throws YouTubeTranscriptException if transcript cannot be fetched
     */
    public TranscriptResult fetchTranscript(String urlOrVideoId, String preferredLanguage)
            throws YouTubeTranscriptException {

        String videoId = extractVideoId(urlOrVideoId);
        if (videoId == null) {
            throw new YouTubeTranscriptException("Could not extract video ID from: " + urlOrVideoId);
        }

        logger.info("Fetching transcript for video ID: {}", videoId);

        try {
            // Fetch the YouTube video page
            String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
            String pageContent = restTemplate.getForObject(videoUrl, String.class);

            if (pageContent == null || pageContent.isEmpty()) {
                throw new YouTubeTranscriptException("Could not fetch video page");
            }

            // Extract video title
            String title = extractTitle(pageContent, videoId);

            // Find caption tracks
            List<CaptionTrack> captionTracks = extractCaptionTracks(pageContent);

            if (captionTracks.isEmpty()) {
                throw new YouTubeTranscriptException("No captions available for video: " + videoId);
            }

            // Select the best caption track
            CaptionTrack selectedTrack = selectCaptionTrack(captionTracks, preferredLanguage);

            logger.info("Selected caption track: language={}", selectedTrack.languageCode);

            // Fetch the transcript XML
            String transcriptXml = restTemplate.getForObject(selectedTrack.baseUrl, String.class);

            if (transcriptXml == null || transcriptXml.isEmpty()) {
                throw new YouTubeTranscriptException("Could not fetch transcript content");
            }

            // Parse the transcript
            List<TranscriptSegment> segments = parseTranscriptXml(transcriptXml);

            // Build full transcript text
            StringBuilder fullTranscript = new StringBuilder();
            for (TranscriptSegment segment : segments) {
                if (fullTranscript.length() > 0) {
                    fullTranscript.append(" ");
                }
                fullTranscript.append(segment.getText());
            }

            // Build metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "youtube");
            metadata.put("videoId", videoId);
            metadata.put("videoUrl", videoUrl);
            metadata.put("title", title);
            metadata.put("language", selectedTrack.languageCode);
            metadata.put("segmentCount", segments.size());
            if (!segments.isEmpty()) {
                double totalDuration = segments.get(segments.size() - 1).getStartTime() +
                                       segments.get(segments.size() - 1).getDuration();
                metadata.put("durationSeconds", totalDuration);
            }

            return new TranscriptResult(
                videoId,
                title,
                fullTranscript.toString(),
                selectedTrack.languageCode,
                segments,
                metadata
            );

        } catch (YouTubeTranscriptException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching transcript for video {}: {}", videoId, e.getMessage(), e);
            throw new YouTubeTranscriptException("Failed to fetch transcript: " + e.getMessage(), e);
        }
    }

    /**
     * Convert transcript result to Spring AI Document for ingestion.
     */
    public Document toDocument(TranscriptResult result) {
        Document document = new Document(result.getTranscript());
        document.getMetadata().putAll(result.getMetadata());
        return document;
    }

    /**
     * Convert transcript result to multiple Documents, one per segment (for finer granularity).
     */
    public List<Document> toSegmentedDocuments(TranscriptResult result) {
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < result.getSegments().size(); i++) {
            TranscriptSegment segment = result.getSegments().get(i);
            Document doc = new Document(segment.getText());
            doc.getMetadata().putAll(result.getMetadata());
            doc.getMetadata().put("segmentIndex", i);
            doc.getMetadata().put("startTime", segment.getStartTime());
            doc.getMetadata().put("duration", segment.getDuration());
            documents.add(doc);
        }

        return documents;
    }

    private String extractTitle(String pageContent, String videoId) {
        // Try to extract title from meta tag
        Pattern titlePattern = Pattern.compile("<meta\\s+name=\"title\"\\s+content=\"([^\"]+)\"");
        Matcher matcher = titlePattern.matcher(pageContent);
        if (matcher.find()) {
            return decodeHtmlEntities(matcher.group(1));
        }

        // Try og:title
        titlePattern = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"");
        matcher = titlePattern.matcher(pageContent);
        if (matcher.find()) {
            return decodeHtmlEntities(matcher.group(1));
        }

        // Fallback
        return "YouTube Video " + videoId;
    }

    private static class CaptionTrack {
        String baseUrl;
        String languageCode;
        boolean isAutoGenerated;
    }

    private List<CaptionTrack> extractCaptionTracks(String pageContent) {
        List<CaptionTrack> tracks = new ArrayList<>();

        // Look for caption tracks in the page data
        Matcher captionsMatcher = CAPTION_TRACKS_PATTERN.matcher(pageContent);
        if (captionsMatcher.find()) {
            String captionsData = captionsMatcher.group(1);

            // Parse individual caption track entries
            // The format is: {"baseUrl":"...","languageCode":"...","..."}
            Pattern trackPattern = Pattern.compile("\\{[^}]*\"baseUrl\":\\s*\"([^\"]+)\"[^}]*\"languageCode\":\\s*\"([^\"]+)\"[^}]*\\}");
            Matcher trackMatcher = trackPattern.matcher(captionsData);

            while (trackMatcher.find()) {
                CaptionTrack track = new CaptionTrack();
                track.baseUrl = trackMatcher.group(1).replace("\\u0026", "&");
                track.languageCode = trackMatcher.group(2);
                track.isAutoGenerated = track.baseUrl.contains("kind=asr");
                tracks.add(track);
            }
        }

        // Alternative pattern for different page formats
        if (tracks.isEmpty()) {
            Pattern altPattern = Pattern.compile("\"captions\":\\s*\\{.*?\"playerCaptionsTracklistRenderer\":\\s*\\{.*?\"captionTracks\":\\s*\\[([^\\]]+)\\]");
            Matcher altMatcher = altPattern.matcher(pageContent);
            if (altMatcher.find()) {
                String captionsData = altMatcher.group(1);
                Pattern urlPattern = Pattern.compile("\"baseUrl\":\\s*\"([^\"]+)\".*?\"languageCode\":\\s*\"([^\"]+)\"");
                Matcher urlMatcher = urlPattern.matcher(captionsData);
                while (urlMatcher.find()) {
                    CaptionTrack track = new CaptionTrack();
                    track.baseUrl = urlMatcher.group(1).replace("\\u0026", "&");
                    track.languageCode = urlMatcher.group(2);
                    track.isAutoGenerated = track.baseUrl.contains("kind=asr");
                    tracks.add(track);
                }
            }
        }

        return tracks;
    }

    private CaptionTrack selectCaptionTrack(List<CaptionTrack> tracks, String preferredLanguage) {
        // First, try to find exact match for preferred language (manual captions preferred)
        if (preferredLanguage != null) {
            for (CaptionTrack track : tracks) {
                if (track.languageCode.equals(preferredLanguage) && !track.isAutoGenerated) {
                    return track;
                }
            }
            // Auto-generated as fallback for preferred language
            for (CaptionTrack track : tracks) {
                if (track.languageCode.equals(preferredLanguage)) {
                    return track;
                }
            }
        }

        // Try English manual captions
        for (CaptionTrack track : tracks) {
            if (track.languageCode.startsWith("en") && !track.isAutoGenerated) {
                return track;
            }
        }

        // Try any manual captions
        for (CaptionTrack track : tracks) {
            if (!track.isAutoGenerated) {
                return track;
            }
        }

        // Return first available (likely auto-generated)
        return tracks.get(0);
    }

    private List<TranscriptSegment> parseTranscriptXml(String xml) {
        List<TranscriptSegment> segments = new ArrayList<>();

        // Parse XML transcript format: <text start="0.0" dur="1.0">text content</text>
        Pattern textPattern = Pattern.compile("<text\\s+start=\"([\\d.]+)\"\\s+dur=\"([\\d.]+)\"[^>]*>([^<]*)</text>");
        Matcher matcher = textPattern.matcher(xml);

        while (matcher.find()) {
            try {
                double start = Double.parseDouble(matcher.group(1));
                double dur = Double.parseDouble(matcher.group(2));
                String text = decodeHtmlEntities(matcher.group(3).trim());

                if (!text.isEmpty()) {
                    segments.add(new TranscriptSegment(text, start, dur));
                }
            } catch (NumberFormatException e) {
                logger.warn("Could not parse timing: start={}, dur={}",
                    matcher.group(1), matcher.group(2));
            }
        }

        return segments;
    }

    private String decodeHtmlEntities(String text) {
        if (text == null) return "";
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("\\n", "\n")
            .replace("\\\"", "\"");
    }

    /**
     * Exception thrown when transcript cannot be fetched.
     */
    public static class YouTubeTranscriptException extends Exception {
        public YouTubeTranscriptException(String message) {
            super(message);
        }

        public YouTubeTranscriptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
