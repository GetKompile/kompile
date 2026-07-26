/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.web;

import ai.kompile.staging.download.HuggingFaceDiscovery;
import ai.kompile.staging.download.HuggingFaceDownloader;
import ai.kompile.staging.web.dto.HuggingFaceDiscoveryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Connected-browser API for resolving and inspecting Hugging Face repositories.
 * Mobile APKs launch this UI externally and retain no network permission.
 */
@RestController
@RequestMapping("/api/staging/huggingface")
public class HuggingFaceDiscoveryController {
    private final HuggingFaceDownloader downloader;

    public HuggingFaceDiscoveryController(HuggingFaceDownloader downloader) {
        this.downloader = downloader;
    }

    @PostMapping("/discover")
    public HuggingFaceDiscovery discover(@RequestBody HuggingFaceDiscoveryRequest request) {
        if (request == null || request.getReference() == null || request.getReference().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Hugging Face repository or URL is required");
        }
        try {
            return downloader.discover(
                    request.getReference(), request.getRevision(), request.getAuthToken());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } catch (IOException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Hugging Face discovery failed: " + unavailable.getMessage(),
                    unavailable);
        }
    }
}
