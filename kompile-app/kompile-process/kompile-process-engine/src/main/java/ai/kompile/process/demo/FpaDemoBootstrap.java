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

package ai.kompile.process.demo;

import ai.kompile.process.controls.ControlDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Bootstraps the FP&A demo data into the process engine on application start.
 * Loads ontology, process definition, controls, and agent specs from
 * {@code classpath:demo/fpa-*.json} resource files.
 *
 * <p>Activated on application startup.
 */
@Component
public class FpaDemoBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FpaDemoBootstrap.class);

    private final ProcessEngineService processEngineService;
    private final ObjectMapper objectMapper;

    public FpaDemoBootstrap(ProcessEngineService processEngineService) {
        this.processEngineService = processEngineService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadDemoData() {
        try {
            loadOntology();
            loadProcessDefinition();
            log.info("FP&A demo data loaded successfully");
        } catch (Exception e) {
            log.warn("Failed to load FP&A demo data (non-fatal): {}", e.getMessage());
        }
    }

    private void loadOntology() throws Exception {
        try (InputStream is = new ClassPathResource("demo/fpa-ontology.json").getInputStream()) {
            OntologySchema schema = objectMapper.readValue(is, OntologySchema.class);
            try {
                processEngineService.getOntology(schema.getId(), 1);
                log.debug("FP&A ontology already exists, skipping");
            } catch (IllegalArgumentException e) {
                processEngineService.createOntology(schema);
                log.info("Created FP&A demo ontology: {} (9 entity types, {} rules)",
                        schema.getName(),
                        schema.getEntityTypes() != null ? schema.getEntityTypes().stream()
                                .mapToInt(et -> et.getRules() != null ? et.getRules().size() : 0).sum() : 0);
            }
        }
    }

    private void loadProcessDefinition() throws Exception {
        try (InputStream is = new ClassPathResource("demo/fpa-process-definition.json").getInputStream()) {
            ProcessDefinition def = objectMapper.readValue(is, ProcessDefinition.class);
            try {
                processEngineService.getProcess(def.getId(), 1);
                log.debug("FP&A process definition already exists, skipping");
            } catch (IllegalArgumentException e) {
                processEngineService.createProcess(def);
                int totalSteps = def.getPhases() != null ? def.getPhases().stream()
                        .mapToInt(p -> p.getSteps() != null ? p.getSteps().size() : 0).sum() : 0;
                log.info("Created FP&A demo process: {} ({} phases, {} steps)",
                        def.getName(),
                        def.getPhases() != null ? def.getPhases().size() : 0,
                        totalSteps);
            }
        }
    }
}
