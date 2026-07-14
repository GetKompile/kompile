/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.staging.web;

import ai.kompile.staging.execution.audio.AudioSynthesisManifest;
import ai.kompile.staging.execution.audio.AudioSynthesisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Standard same-origin pull contract for completed model-generated audio files.
 */
@RestController
@RequestMapping("/api/audio")
public class AudioSynthesisController {

    private static final Logger LOG = LoggerFactory.getLogger(AudioSynthesisController.class);

    private final AudioSynthesisService service;
    private final String transferToken;

    public AudioSynthesisController(
            AudioSynthesisService service,
            @Value("${kompile.staging.artifact-transfer-token:}") String transferToken) {
        this.service = service;
        this.transferToken = transferToken == null ? "" : transferToken;
    }

    @PostMapping("/synthesize")
    public ResponseEntity<?> synthesize(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody SynthesisRequest request) {
        if (!authenticated(authorization)) {
            return error(HttpStatus.UNAUTHORIZED, "AUDIO_TRANSFER_UNAUTHORIZED",
                    "A valid staging artifact bearer token is required");
        }
        try {
            AudioSynthesisManifest manifest = service.synthesize(
                    new AudioSynthesisService.SynthesisCommand(request.runId(), request.text(),
                            request.voice(), request.language(), request.configuration()));
            return ResponseEntity.ok(manifest);
        } catch (AudioSynthesisService.IdempotencyConflictException conflict) {
            return error(HttpStatus.CONFLICT, "AUDIO_RUN_CONFLICT", conflict.getMessage());
        } catch (AudioSynthesisService.ModelUnavailableException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIO_MODEL_UNAVAILABLE",
                    unavailable.getMessage());
        } catch (AudioSynthesisService.InvalidGeneratedFileException invalid) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_GENERATION_INVALID",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "AUDIO_REQUEST_INVALID", invalid.getMessage());
        } catch (Exception failure) {
            LOG.error("Audio synthesis failed for run {}", request.runId(), failure);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_SYNTHESIS_FAILED",
                    "Audio synthesis failed");
        }
    }

    @GetMapping("/artifacts/{artifactReference}/content")
    public ResponseEntity<?> content(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID artifactReference) {
        if (!authenticated(authorization)) {
            return error(HttpStatus.UNAUTHORIZED, "AUDIO_TRANSFER_UNAUTHORIZED",
                    "A valid staging artifact bearer token is required");
        }
        try {
            AudioSynthesisService.ArtifactContent content = service.openArtifact(artifactReference);
            Resource resource = new FileSystemResource(content.path());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(content.manifest().mediaType()))
                    .contentLength(content.manifest().byteLength())
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                    .header("X-Content-Type-Options", "nosniff")
                    .eTag('"' + content.manifest().contentHash() + '"')
                    .body(resource);
        } catch (AudioSynthesisService.ArtifactNotFoundException missing) {
            return error(HttpStatus.NOT_FOUND, "AUDIO_ARTIFACT_NOT_FOUND", missing.getMessage());
        } catch (AudioSynthesisService.ArtifactGoneException gone) {
            return error(HttpStatus.GONE, "AUDIO_ARTIFACT_GONE", gone.getMessage());
        } catch (Exception failure) {
            LOG.error("Audio artifact transfer failed for {}", artifactReference, failure);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_TRANSFER_FAILED",
                    "Audio artifact transfer failed");
        }
    }

    @RequestMapping(value = "/artifacts/{artifactReference}/content",
            method = org.springframework.web.bind.annotation.RequestMethod.HEAD)
    public ResponseEntity<?> head(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID artifactReference) {
        if (!authenticated(authorization)) {
            return error(HttpStatus.UNAUTHORIZED, "AUDIO_TRANSFER_UNAUTHORIZED",
                    "A valid staging artifact bearer token is required");
        }
        try {
            AudioSynthesisService.ArtifactContent content = service.openArtifact(artifactReference);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(content.manifest().mediaType()))
                    .contentLength(content.manifest().byteLength())
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                    .header("X-Content-Type-Options", "nosniff")
                    .eTag('"' + content.manifest().contentHash() + '"')
                    .build();
        } catch (AudioSynthesisService.ArtifactNotFoundException missing) {
            return error(HttpStatus.NOT_FOUND, "AUDIO_ARTIFACT_NOT_FOUND", missing.getMessage());
        } catch (AudioSynthesisService.ArtifactGoneException gone) {
            return error(HttpStatus.GONE, "AUDIO_ARTIFACT_GONE", gone.getMessage());
        } catch (Exception failure) {
            LOG.error("Audio artifact HEAD failed for {}", artifactReference, failure);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIO_TRANSFER_FAILED",
                    "Audio artifact transfer failed");
        }
    }

    private boolean authenticated(String authorization) {
        if (transferToken.isBlank() || authorization == null
                || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = transferToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                              String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "code", code,
                "message", message == null ? code : message));
    }

    public record SynthesisRequest(@NotNull UUID runId, @NotBlank String text,
                                   String voice, String language,
                                   Map<String, Object> configuration) {
    }
}
