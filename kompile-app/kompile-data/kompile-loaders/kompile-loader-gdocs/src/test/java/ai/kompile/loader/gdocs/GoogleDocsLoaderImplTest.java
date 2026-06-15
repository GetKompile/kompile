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

package ai.kompile.loader.gdocs;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocsLoaderImplTest {

    private GoogleDocsLoaderImpl loader;

    @BeforeEach
    void setUp() {
        loader = new GoogleDocsLoaderImpl();
    }

    // ── getName() ──────────────────────────────────────────────────────

    @Test
    void getNameReturnsGoogleDocsLoader() {
        assertEquals("Google Docs Loader", loader.getName());
    }

    // ── supports() ─────────────────────────────────────────────────────

    @Test
    void supportsGdocsSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDOCS)
                .build();
        assertTrue(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportGdriveSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDRIVE)
                .build();
        assertFalse(loader.supports(descriptor));
    }

    @Test
    void doesNotSupportGmailSourceType() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GMAIL)
                .build();
        assertFalse(loader.supports(descriptor));
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

    // ── load() — validation ────────────────────────────────────────────

    @Test
    void throwsWhenMetadataIsNull() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDOCS)
                .metadata(null)
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void throwsWhenAccessTokenIsMissing() {
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDOCS)
                .metadata(Map.of("driveQuery", "name contains 'test'"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void throwsWhenAccessTokenIsBlank() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("accessToken", "   ");
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.GDOCS)
                .metadata(meta)
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }
}
