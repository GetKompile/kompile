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

package ai.kompile.app.web.controllers;

import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.config.EmbeddingRestartConfig;
import ai.kompile.embedding.anserini.config.EmbeddingRestartConfigService;
import ai.kompile.embedding.anserini.config.EmbeddingRestartStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REST API for the embedding-subprocess restart governor (manual toggle + native-crash
 * circuit breaker). The governor state lives on the {@link AnseriniEmbeddingModelImpl}
 * {@code @Service} in this same (app-main) JVM, so the controller talks to the live bean
 * directly via an {@link ObjectProvider} (which preserves the bean's {@code @Lazy} semantics).
 */
@RestController
@RequestMapping("/api/embedding-restart")
public class EmbeddingRestartController {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingRestartController.class);

    private final EmbeddingRestartConfigService configService;
    private final ObjectProvider<AnseriniEmbeddingModelImpl> embeddingModelProvider;

    public EmbeddingRestartController(EmbeddingRestartConfigService configService,
                                      ObjectProvider<AnseriniEmbeddingModelImpl> embeddingModelProvider) {
        this.configService = configService;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /** Current governor state: persisted toggle/threshold plus live paused/crash state. */
    @GetMapping("/status")
    public ResponseEntity<EmbeddingRestartStatus> status() {
        return ResponseEntity.ok(currentStatus());
    }

    /** Persist the toggle + threshold. Re-enabling auto-restart also clears a prior pause. */
    @PutMapping("/config")
    public ResponseEntity<EmbeddingRestartStatus> updateConfig(@RequestBody EmbeddingRestartConfig config)
            throws IOException {
        EmbeddingRestartConfig saved = configService.save(config);
        log.info("Embedding restart config updated via API: autoRestartEnabled={}, nativeCrashThreshold={}",
                saved.isAutoRestartEnabledOrDefault(), saved.nativeCrashThresholdOrDefault());
        if (saved.isAutoRestartEnabledOrDefault()) {
            AnseriniEmbeddingModelImpl model = embeddingModelProvider.getIfAvailable();
            if (model != null && model.getRestartGovernorStatus().isRestartsPaused()) {
                model.resumeRestarts();
            }
        }
        return ResponseEntity.ok(currentStatus());
    }

    /** Manually clear a paused/tripped state and attempt to bring the subprocess back. */
    @PostMapping("/resume")
    public ResponseEntity<EmbeddingRestartStatus> resume() {
        AnseriniEmbeddingModelImpl model = embeddingModelProvider.getIfAvailable();
        if (model != null) {
            boolean initialized = model.resumeRestarts();
            log.info("Embedding restart resume requested via API — initialized={}", initialized);
        } else {
            log.info("Embedding restart resume requested but embedding model is not available");
        }
        return ResponseEntity.ok(currentStatus());
    }

    private EmbeddingRestartStatus currentStatus() {
        AnseriniEmbeddingModelImpl model = embeddingModelProvider.getIfAvailable();
        if (model != null) {
            return model.getRestartGovernorStatus();
        }
        // Embedding bean not present (e.g. embedding disabled / not yet created) — report config only.
        EmbeddingRestartConfig cfg = configService.getConfig();
        return EmbeddingRestartStatus.builder()
                .autoRestartEnabled(cfg.isAutoRestartEnabledOrDefault())
                .nativeCrashThreshold(cfg.nativeCrashThresholdOrDefault())
                .restartsPaused(false)
                .consecutiveNativeCrashes(0)
                .pausedReason(null)
                .lastCrashReason(null)
                .subprocessRunning(false)
                .modelAvailable(false)
                .build();
    }
}
