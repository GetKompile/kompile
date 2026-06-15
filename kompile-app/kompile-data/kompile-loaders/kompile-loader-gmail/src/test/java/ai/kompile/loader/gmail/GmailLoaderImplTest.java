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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gmail;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GmailLoaderImplTest {

    private GmailLoaderImpl loader;

    @BeforeEach
    void setUp() {
        loader = new GmailLoaderImpl();
    }

    // ── getName() ───────────────────────────────────────────────────────

    @Test
    void getNameReturnsGmailLoader() {
        assertEquals("Gmail Loader", loader.getName());
    }

    // ── supports() ──────────────────────────────────────────────────────

    @Test
    void supportsGmailSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .build();
        assertTrue(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportUrlSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.URL)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportFileSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportEmailSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.EMAIL)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportGoogleWorkspaceSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    // ── load() — validation ─────────────────────────────────────────────

    @Test
    void throwsWhenMetadataIsNull() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .metadata(null)
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void throwsWhenAccessTokenIsMissing() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .metadata(Map.of("gmailQuery", "label:inbox"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void throwsWhenAccessTokenIsBlank() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("accessToken", "   ");
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .metadata(meta)
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }
}
