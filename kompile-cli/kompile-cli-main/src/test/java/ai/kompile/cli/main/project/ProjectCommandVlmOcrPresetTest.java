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
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCommandVlmOcrPresetTest {

    @TempDir
    Path tempDir;

    @Test
    void createPresetSeedsVlmOcrProjectWithoutRunningPipeline() throws Exception {
        Path projectRoot = tempDir.resolve("vlm-ocr-project");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "vlm-ocr-project",
                "--preset", "vlm-ocr",
                "--source", "data/input_documents/uploads",
                "--pdf-routing", "FORCE_VLM",
                "--vlm-model", "smoldocling-256m",
                "--schema-preset", "fpna-cpg-channel-v1"));

        String manifest = Files.readString(projectRoot.resolve("kompile.project.json"), StandardCharsets.UTF_8);
        String openState = Files.readString(projectRoot.resolve(".kompile/project/open.json"), StandardCharsets.UTF_8);
        String pipeline = Files.readString(projectRoot.resolve("data/pipelines/vlm-ocr-pipeline.json"), StandardCharsets.UTF_8);
        String routing = Files.readString(projectRoot.resolve("data/pipelines/vlm-ocr-routing.json"), StandardCharsets.UTF_8);
        String stagingRegistry = Files.readString(projectRoot.resolve("data/models/registry.json"), StandardCharsets.UTF_8);
        String prompt = Files.readString(projectRoot.resolve("data/prompt-templates/vlm_ocr_extract.json"), StandardCharsets.UTF_8);
        String runbook = Files.readString(projectRoot.resolve("data/pipelines/vlm-ocr-runbook.md"), StandardCharsets.UTF_8);
        String script = Files.readString(projectRoot.resolve("scripts/run-vlm-ocr.sh"), StandardCharsets.UTF_8);

        assertTrue(manifest.contains("\"id\" : \"ocr-output\""));
        assertTrue(manifest.contains("\"role\" : \"VLM\""));
        assertTrue(manifest.contains("\"pipelineId\" : \"vlm-ocr-pdf\""));
        assertTrue(manifest.contains("\"id\" : \"vlm-ocr-docs\""));
        assertTrue(manifest.contains("\"multimodal\" : true"));
        assertTrue(manifest.contains("\"vlmModel\" : \"smoldocling-256m\""));
        assertTrue(manifest.contains("\"schemaPresetId\" : \"fpna-cpg-channel-v1\""));
        assertTrue(manifest.contains("\"id\" : \"vlm-ocr-ingest\""));
        assertTrue(manifest.contains("\"id\" : \"run-vlm-ocr\""));

        assertTrue(pipeline.contains("\"kind\" : \"VLM\""));
        assertTrue(pipeline.contains("\"pipelineSpec\""));
        assertTrue(pipeline.contains("\"runnerClassName\" : \"ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner\""));
        assertTrue(pipeline.contains("\"pdfRoutingMode\" : \"FORCE_VLM\""));
        assertTrue(routing.contains("\"pipelineDefinitionPath\" : \"data/pipelines/vlm-ocr-pipeline.json\""));
        assertTrue(routing.contains("\"fileExtensions\" : [\".pdf\"]"));
        assertTrue(routing.contains("\".png\""));
        assertTrue(stagingRegistry.contains("\"model_id\" : \"smoldocling-256m\""));
        assertTrue(stagingRegistry.contains("\"type\" : \"vlm_pipeline\""));
        assertTrue(stagingRegistry.contains("\"model_file\" : \"pipeline.json\""));
        assertTrue(stagingRegistry.contains("\"source_repository\" : \"ds4sd/SmolDocling-256M-preview\""));
        assertTrue(prompt.contains("Preserve table cells and reading order"));
        assertTrue(runbook.contains("kompile project workflow-run --root . --id vlm-ocr-ingest --dry-run"));
        assertTrue(runbook.contains("vlm-ocr-routing.json"));
        assertTrue(script.contains("--dry-run"));
        assertTrue(script.contains("KOMPILE_BIN=\"${KOMPILE_BIN:-kompile}\""));
        assertTrue(openState.contains("\"promptTemplateCount\" : 1"));
        assertTrue(Files.isDirectory(projectRoot.resolve("data/ocr/vlm-ocr-docs")));
    }

    @Test
    void modelRuntimeInventoryRecognizesVlmPipelineArtifacts() throws Exception {
        Path projectRoot = tempDir.resolve("vlm-runtime-project");
        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "vlm-runtime-project",
                "--preset", "vlm-ocr",
                "--vlm-model", "smoldocling-256m"));

        Path descriptor = projectRoot.resolve(
                "data/models/vlm-pipelines/smoldocling-256m/pipeline.json");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, "{}", StandardCharsets.UTF_8);

        Map<String, Object> descriptorInventory = LocalProjectModelBootstrap.inventory(projectRoot).stream()
                .filter(model -> "smoldocling-256m".equals(model.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals(true, descriptorInventory.get("ready"));
        assertEquals(descriptor.toAbsolutePath().normalize().toString(),
                descriptorInventory.get("resolvedArtifact"));

        Files.delete(descriptor);
        Path sourceComponent = descriptor.getParent().resolve("decoder_model_merged.onnx");
        Files.write(sourceComponent, new byte[]{1, 2, 3});
        Map<String, Object> sourceOnlyInventory = LocalProjectModelBootstrap.inventory(projectRoot).stream()
                .filter(model -> "smoldocling-256m".equals(model.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals(false, sourceOnlyInventory.get("ready"));
        assertNull(sourceOnlyInventory.get("resolvedArtifact"),
                "Raw VLM ONNX is a staging input, not a runnable native artifact");

        Path promotedComponent = descriptor.getParent().resolve("decoder.sdz");
        Files.write(promotedComponent, new byte[]{4, 5, 6});
        Map<String, Object> decoderOnlyInventory = LocalProjectModelBootstrap.inventory(projectRoot).stream()
                .filter(model -> "smoldocling-256m".equals(model.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals(false, decoderOnlyInventory.get("ready"),
                "A decoder without a converted vision encoder is not a runnable VLM");

        Files.write(descriptor.getParent().resolve("vision_encoder.sdz"), new byte[]{7, 8, 9});
        Map<String, Object> promotedInventory = LocalProjectModelBootstrap.inventory(projectRoot).stream()
                .filter(model -> "smoldocling-256m".equals(model.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals(true, promotedInventory.get("ready"));
        assertEquals(promotedComponent.toAbsolutePath().normalize().toString(),
                promotedInventory.get("resolvedArtifact"));
    }

    @Test
    void registryEntryUsesSharedArtifactCompletenessAndLifecycleRules() throws Exception {
        Path projectRoot = tempDir.resolve("registry-project");
        Path modelDirectory = projectRoot.resolve("data/models/encoders/test-model");
        Files.createDirectories(modelDirectory);
        KompileProjectModel model = new KompileProjectModel();
        model.setId("test-model");
        model.setModelId("test-model");
        model.setRegistryModelId("test-model");
        model.setPath("encoders/test-model");
        model.setLifecycle(KompileProjectLifecycleState.ACTIVE);
        model.setMetadata(Map.of("registry.modelFile", "model.sdnb"));

        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"staged\""));
        Files.writeString(modelDirectory.resolve("other.shard0-of-2.sdnb"), "wrong-0");
        Files.writeString(modelDirectory.resolve("other.shard1-of-2.sdnb"), "wrong-1");
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"staged\""));

        Files.writeString(modelDirectory.resolve("model.shard0-of-2.sdnb"), "expected-0");
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"staged\""));
        Files.writeString(modelDirectory.resolve("model.shard1-of-2.sdnb"), "expected-1");
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"active\""));

        model.setLifecycle(KompileProjectLifecycleState.DRAFT);
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"staged\""));
        model.setLifecycle(KompileProjectLifecycleState.PAUSED);
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"staged\""));
        model.setLifecycle(KompileProjectLifecycleState.ARCHIVED);
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"deprecated\""));
        model.setLifecycle(KompileProjectLifecycleState.DEPRECATED);
        assertTrue(ProjectModelCommand.stagingModelEntryJson(projectRoot, model)
                .contains("\"status\":\"deprecated\""));
    }

    @Test
    void projectConfigSetWritesProjectLocalJson() throws Exception {
        Path projectRoot = tempDir.resolve("config-project");

        assertEquals(0, execute("project", "create",
                "--root", projectRoot.toString(),
                "--name", "config-project",
                "--no-auto-detect"));

        assertEquals(0, execute("project", "config", "set",
                "--root", projectRoot.toString(),
                "graph-extraction-config.crawlGraphExtractionParallelism=2",
                "graph-extraction-config.extractionModelPriority=[\"local/lfm2\",\"opencode/test\"]",
                "resource-scheduler-config.heavyMemoryOpEstimatesMb.embedding=8192"));

        String graphConfig = Files.readString(projectRoot.resolve("config/graph-extraction-config.json"), StandardCharsets.UTF_8);
        String resourceConfig = Files.readString(projectRoot.resolve("config/resource-scheduler-config.json"), StandardCharsets.UTF_8);

        assertFalse(graphConfig.contains("\"enabled\""));
        assertTrue(graphConfig.contains("\"crawlGraphExtractionParallelism\" : 2"));
        assertTrue(graphConfig.contains("\"local/lfm2\""));
        assertTrue(resourceConfig.contains("\"heavyMemoryOpEstimatesMb\""));
        assertTrue(resourceConfig.contains("\"embedding\" : 8192"));
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
