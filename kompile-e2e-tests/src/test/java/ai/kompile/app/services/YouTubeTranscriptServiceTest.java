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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YouTubeTranscriptServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private YouTubeTranscriptService service;

    @BeforeEach
    void setUp() {
        service = new YouTubeTranscriptService(restTemplate);
    }

    // ── extractVideoId ────────────────────────────────────────────────────────

    @Test
    void testExtractVideoId_standardWatchUrl() {
        String id = service.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", id);
    }

    @Test
    void testExtractVideoId_shortUrl() {
        String id = service.extractVideoId("https://youtu.be/dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", id);
    }

    @Test
    void testExtractVideoId_embedUrl() {
        String id = service.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", id);
    }

    @Test
    void testExtractVideoId_vUrl() {
        String id = service.extractVideoId("https://www.youtube.com/v/dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", id);
    }

    @Test
    void testExtractVideoId_rawVideoId() {
        String id = service.extractVideoId("dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", id);
    }

    @Test
    void testExtractVideoId_nullInput_returnsNull() {
        assertNull(service.extractVideoId(null));
    }

    @Test
    void testExtractVideoId_emptyInput_returnsNull() {
        assertNull(service.extractVideoId(""));
    }

    @Test
    void testExtractVideoId_blankInput_returnsNull() {
        assertNull(service.extractVideoId("   "));
    }

    @Test
    void testExtractVideoId_withQueryParams() {
        String id = service.extractVideoId(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s&list=PLabc");
        assertEquals("dQw4w9WgXcQ", id);
    }

    @Test
    void testExtractVideoId_videoIdExactly11Chars() {
        // A raw 11-char alphanumeric+hyphen+underscore string
        String id = service.extractVideoId("abcDE12_-fg");
        assertEquals("abcDE12_-fg", id);
    }

    @Test
    void testExtractVideoId_invalidUrl_returnsNull() {
        // Not a YouTube URL and not a valid 11-char ID
        String id = service.extractVideoId("https://example.com/video/123");
        assertNull(id);
    }

    // ── TranscriptResult POJO ──────────────────────────────────────────────────

    @Test
    void testTranscriptResult_gettersWork() {
        YouTubeTranscriptService.TranscriptSegment seg =
                new YouTubeTranscriptService.TranscriptSegment("Hello", 0.0, 2.5);
        assertEquals("Hello", seg.getText());
        assertEquals(0.0, seg.getStartTime(), 0.001);
        assertEquals(2.5, seg.getDuration(), 0.001);
    }

    @Test
    void testTranscriptSegment_constructsCorrectly() {
        YouTubeTranscriptService.TranscriptSegment seg =
                new YouTubeTranscriptService.TranscriptSegment("World text", 5.5, 3.2);
        assertEquals("World text", seg.getText());
        assertEquals(5.5, seg.getStartTime(), 0.001);
        assertEquals(3.2, seg.getDuration(), 0.001);
    }

    @Test
    void testTranscriptResult_constructsCorrectly() {
        YouTubeTranscriptService.TranscriptResult result =
                new YouTubeTranscriptService.TranscriptResult(
                        "videoId123", "My Title", "Full transcript text",
                        "en", java.util.List.of(), java.util.Map.of()
                );
        assertEquals("videoId123", result.getVideoId());
        assertEquals("My Title", result.getTitle());
        assertEquals("Full transcript text", result.getTranscript());
        assertEquals("en", result.getLanguage());
        assertNotNull(result.getSegments());
        assertNotNull(result.getMetadata());
    }

    // ── fetchTranscript (verify exception thrown when ID cannot be extracted) ──

    @Test
    void testFetchTranscript_nullVideoId_throwsYouTubeException() {
        assertThrows(YouTubeTranscriptService.YouTubeTranscriptException.class,
                () -> service.fetchTranscript(null, "en"));
    }

    @Test
    void testFetchTranscript_blankVideoId_throwsYouTubeException() {
        assertThrows(YouTubeTranscriptService.YouTubeTranscriptException.class,
                () -> service.fetchTranscript("", "en"));
    }

    @Test
    void testFetchTranscript_nonYouTubeUrl_throwsYouTubeException() {
        assertThrows(YouTubeTranscriptService.YouTubeTranscriptException.class,
                () -> service.fetchTranscript("https://example.com/video", "en"));
    }
}
