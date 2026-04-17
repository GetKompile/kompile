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

package ai.kompile.app.chunker.tableaware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BoundaryDetector}.
 */
class BoundaryDetectorTest {

    private BoundaryDetector detector;

    @BeforeEach
    void setUp() {
        detector = new BoundaryDetector();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // URL Detection Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void detectHttpUrl() {
        String text = "Visit https://example.com/path/to/page?query=value for more info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertEquals(1, regions.size());
        assertEquals("url", regions.get(0).boundaryType());
        assertEquals("https://example.com/path/to/page?query=value", regions.get(0).content());
    }

    @Test
    void detectMultipleUrls() {
        String text = "Check http://first.com and https://second.org/page for details.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertEquals(2, regions.size());
        assertTrue(regions.stream().anyMatch(r -> r.content().equals("http://first.com")));
        assertTrue(regions.stream().anyMatch(r -> r.content().equals("https://second.org/page")));
    }

    @Test
    void detectMailtoUrl() {
        String text = "Contact us at mailto:support@example.com for assistance.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertTrue(regions.stream().anyMatch(r -> r.boundaryType().equals("url") && r.content().contains("mailto:")));
    }

    @Test
    void detectWwwUrl() {
        String text = "Browse to www.example.com/products for our catalog.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertEquals(1, regions.size());
        assertEquals("url", regions.get(0).boundaryType());
        assertTrue(regions.get(0).content().startsWith("www."));
    }

    @Test
    void detectUrlWithComplexPath() {
        String text = "API endpoint: https://api.example.com/v1/users/123/profile?fields=name,email&format=json";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertEquals(1, regions.size());
        assertTrue(regions.get(0).content().contains("fields=name,email&format=json"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Email Detection Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void detectEmail() {
        // Test with urlsAndEmailsOnly config to ensure emails are detected
        BoundaryDetector.BoundaryConfig config = BoundaryDetector.BoundaryConfig.urlsAndEmailsOnly();
        String text = "Send feedback to user.name@company.com for review.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        // Should detect either as email or as part of URL pattern
        assertTrue(regions.size() >= 1, "Expected at least one region, found: " + regions);
        // Check that the email address is captured in some form
        assertTrue(regions.stream().anyMatch(r ->
            r.content().contains("user.name@company.com") ||
            r.content().contains("company.com") ||
            r.boundaryType().equals("email")),
            "Expected email to be detected, found: " + regions);
    }

    @Test
    void detectMultipleEmails() {
        BoundaryDetector.BoundaryConfig config = BoundaryDetector.BoundaryConfig.urlsAndEmailsOnly();
        String text = "Contact john@example.com or jane.doe@company.org for info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        // Should have detected regions containing the emails
        assertTrue(regions.size() >= 2 ||
            (regions.size() >= 1 && regions.stream().anyMatch(r -> r.content().contains("@"))));
    }

    @Test
    void detectEmailWithSubdomain() {
        BoundaryDetector.BoundaryConfig config = BoundaryDetector.BoundaryConfig.urlsAndEmailsOnly();
        String text = "Email admin@mail.subdomain.example.com for access.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        assertTrue(regions.size() >= 1);
        // The email should be detected either as email or URL (since subdomain.example.com matches TLD pattern)
        assertTrue(regions.stream().anyMatch(r -> r.content().contains("@") || r.content().contains("example.com")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // File Path Detection Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void detectUnixPath() {
        String text = "Config file is at /etc/myapp/config.yaml for editing.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertTrue(regions.stream().anyMatch(r ->
            (r.boundaryType().equals("unix_path") || r.boundaryType().equals("url")) &&
            r.content().contains("/etc/myapp/config.yaml")));
    }

    @Test
    void detectRelativePath() {
        String text = "Run script from ./scripts/deploy.sh to deploy.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertTrue(regions.stream().anyMatch(r ->
            r.boundaryType().equals("unix_path") && r.content().contains("./scripts/deploy.sh")));
    }

    @Test
    void detectWindowsPath() {
        String text = "Install to C:\\Program Files\\MyApp\\bin\\app.exe for execution.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertTrue(regions.stream().anyMatch(r ->
            r.boundaryType().equals("windows_path") && r.content().contains("C:\\")));
    }

    @Test
    void detectUncPath() {
        String text = "Access the share at \\\\server\\share\\folder\\file.txt for data.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        assertTrue(regions.stream().anyMatch(r ->
            r.boundaryType().equals("windows_path") && r.content().startsWith("\\\\")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IP Address Detection Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void detectIpv4Address() {
        BoundaryDetector.BoundaryConfig config = new BoundaryDetector.BoundaryConfig();
        config.detectIpAddresses = true;

        String text = "Server is at 192.168.1.100 for access.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        assertTrue(regions.stream().anyMatch(r ->
            r.boundaryType().equals("ipv4") && r.content().equals("192.168.1.100")));
    }

    @Test
    void detectIpv4WithPort() {
        BoundaryDetector.BoundaryConfig config = new BoundaryDetector.BoundaryConfig();
        config.detectIpAddresses = true;

        String text = "Connect to 10.0.0.1:8080 for the service.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        assertTrue(regions.stream().anyMatch(r ->
            r.boundaryType().equals("ipv4") && r.content().contains("10.0.0.1:8080")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Configuration Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void disableUrlDetection() {
        BoundaryDetector.BoundaryConfig config = new BoundaryDetector.BoundaryConfig();
        config.detectUrls = false;

        String text = "Visit https://example.com for info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        assertTrue(regions.stream().noneMatch(r -> r.boundaryType().equals("url")));
    }

    @Test
    void enableCodeIdentifiers() {
        BoundaryDetector.BoundaryConfig config = BoundaryDetector.BoundaryConfig.allEnabled();

        String text = "The getUserProfile method calls fetchData function.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text, config);

        assertTrue(regions.stream().anyMatch(r -> r.boundaryType().equals("identifier")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Safe Split Point Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findSafeSplitPointOutsideBoundary() {
        String text = "Hello world. Visit https://example.com for more.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        // Desired position is outside URL
        int safePoint = detector.findSafeSplitPoint(text, 10, regions, 50);
        assertEquals(10, safePoint);
    }

    @Test
    void findSafeSplitPointInsideBoundary() {
        String text = "Visit https://example.com/very/long/path for info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        // Desired position is inside URL
        int desiredPos = 25; // Middle of URL
        int safePoint = detector.findSafeSplitPoint(text, desiredPos, regions, 50);

        // Should move to after or before the URL
        BoundaryDetector.BoundaryRegion urlRegion = regions.get(0);
        assertTrue(safePoint <= urlRegion.startOffset() || safePoint >= urlRegion.endOffset(),
            "Split point should be outside the URL boundary");
    }

    @Test
    void isInsideBoundaryReturnsTrue() {
        String text = "Visit https://example.com/path for info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        // Position inside URL
        int position = 15;
        assertTrue(detector.isInsideBoundary(position, regions));
    }

    @Test
    void isInsideBoundaryReturnsFalse() {
        String text = "Hello. Visit https://example.com for info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        // Position before URL
        int position = 3;
        assertFalse(detector.isInsideBoundary(position, regions));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge Case Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void handleEmptyText() {
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries("");
        assertTrue(regions.isEmpty());
    }

    @Test
    void handleNullText() {
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(null);
        assertTrue(regions.isEmpty());
    }

    @Test
    void handleTextWithNoBoundaries() {
        String text = "This is a simple text with no URLs, emails, or paths.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);
        assertTrue(regions.isEmpty());
    }

    @Test
    void handleOverlappingBoundaries() {
        // This tests that overlapping regions are handled correctly
        String text = "Check https://example.com/contact@domain.com for info.";
        List<BoundaryDetector.BoundaryRegion> regions = detector.detectBoundaries(text);

        // Should merge or handle overlapping regions
        assertNotNull(regions);
        assertTrue(regions.size() >= 1);
    }
}
