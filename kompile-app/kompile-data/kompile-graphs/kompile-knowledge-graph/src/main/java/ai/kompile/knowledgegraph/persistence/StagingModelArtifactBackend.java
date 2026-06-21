/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link ModelArtifactBackend} that delegates {@code SAMEDIFF_CHECKPOINT} artifacts to the
 * kompile staging registry service.
 *
 * <p>Only {@link ModelArtifactType#SAMEDIFF_CHECKPOINT} is supported and only when
 * {@code kompile.staging.url} is configured. When the staging URL is blank, {@link #supports}
 * returns {@code false} so the {@link ModelArtifactRouter} falls through to the
 * {@link FileModelArtifactBackend}.</p>
 *
 * <p>Priority {@code 10} — takes precedence over the file backend for checkpoints.</p>
 */
@Slf4j
@Component
public class StagingModelArtifactBackend implements ModelArtifactBackend {

    @Value("${kompile.staging.url:}")
    private String stagingUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // ── ModelArtifactBackend ──────────────────────────────────────────────────

    @Override
    public boolean supports(ModelArtifactType type) {
        return ModelArtifactType.SAMEDIFF_CHECKPOINT == type
                && stagingUrl != null && !stagingUrl.isBlank();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void store(ModelArtifactRef ref, Path sourceFile) throws IOException {
        requireStagingUrl();
        String url = stagingUrl + "/api/staging/upload";
        log.debug("StagingModelArtifactBackend: uploading {} to {}", ref, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(sourceFile.toFile()));
        body.add("modelId", ref.artifactId());
        body.add("factSheetId", ref.factSheetId());
        if (ref.algorithm() != null) {
            body.add("algorithm", ref.algorithm());
        }

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IOException("Staging upload failed for " + ref + ": HTTP " + response.getStatusCode());
        }
        log.info("StagingModelArtifactBackend: uploaded {} → status {}", ref, response.getStatusCode());
    }

    @Override
    public Path retrieve(ModelArtifactRef ref, Path targetFile) throws IOException {
        requireStagingUrl();
        String url = stagingUrl + "/api/staging/registry/model/" + ref.artifactId() + "/download/model";
        log.debug("StagingModelArtifactBackend: downloading {} from {}", ref, url);

        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IOException("Staging download failed for " + ref + ": HTTP " + response.getStatusCode());
        }

        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, response.getBody());
        log.info("StagingModelArtifactBackend: downloaded {} → {}", ref, targetFile);
        return targetFile;
    }

    @Override
    public List<ModelArtifactRef> list(String factSheetId) {
        // Staging registry does not expose a cheap list-by-factSheetId endpoint.
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireStagingUrl() {
        if (stagingUrl == null || stagingUrl.isBlank()) {
            throw new UnsupportedOperationException(
                    "StagingModelArtifactBackend: kompile.staging.url is not configured");
        }
    }
}
