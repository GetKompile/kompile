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

package ai.kompile.app.config;

import ai.kompile.app.services.TritonCacheService;
import ai.kompile.app.services.VlmOrchestrationConfigService;
import ai.kompile.ocr.models.pipeline.VlmDocumentPipeline;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Wires VLM orchestration config settings into the VlmDocumentPipeline.
 * Bridges kompile-app-main services to kompile-ocr-models components.
 */
@Configuration
public class VlmOrchestrationWiring {

    private static final Logger log = LoggerFactory.getLogger(VlmOrchestrationWiring.class);

    @Autowired(required = false)
    private VlmDocumentPipeline vlmDocumentPipeline;

    @Autowired
    private VlmOrchestrationConfigService configService;

    @Autowired
    private TritonCacheService tritonCacheService;

    @PostConstruct
    public void wireOrchestrationConfig() {
        if (vlmDocumentPipeline == null) {
            log.debug("VlmDocumentPipeline not available, skipping orchestration wiring");
            return;
        }

        VlmOrchestrationConfig config = configService.getConfig();

        vlmDocumentPipeline.setReleaseEncoderAfterEncoding(
                Boolean.TRUE.equals(config.releaseEncoderAfterEncoding()));
        vlmDocumentPipeline.setEncoderDeviceId(
                config.encoderDeviceId() != null ? config.encoderDeviceId() : -1);
        vlmDocumentPipeline.setDecoderDeviceId(
                config.decoderDeviceId() != null ? config.decoderDeviceId() : -1);
        vlmDocumentPipeline.setTritonCacheEnabled(
                Boolean.TRUE.equals(config.tritonCacheEnabled()));
        vlmDocumentPipeline.setTritonAutoImport(
                Boolean.TRUE.equals(config.tritonAutoImport()));
        vlmDocumentPipeline.setTritonAutoExport(
                Boolean.TRUE.equals(config.tritonAutoExport()));

        // Wire Triton cache import/export callbacks
        vlmDocumentPipeline.setTritonCacheImporter(() -> {
            String modelId = getModelId();
            if (modelId != null) {
                tritonCacheService.importCache(modelId);
            }
        });
        vlmDocumentPipeline.setTritonCacheExporter(() -> {
            String modelId = getModelId();
            if (modelId != null) {
                tritonCacheService.exportCache(modelId);
            }
        });

        log.info("VLM orchestration config wired: releaseEncoder={}, encoderDevice={}, decoderDevice={}, tritonCache={}",
                config.releaseEncoderAfterEncoding(),
                config.encoderDeviceId(),
                config.decoderDeviceId(),
                config.tritonCacheEnabled());
    }

    private String getModelId() {
        return vlmDocumentPipeline != null ? vlmDocumentPipeline.getModelId() : null;
    }
}
