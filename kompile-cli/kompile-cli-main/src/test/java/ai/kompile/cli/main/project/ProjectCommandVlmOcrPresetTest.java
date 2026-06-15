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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "--vlm-model", "smoldocling-256m"));

        String manifest = Files.readString(projectRoot.resolve("kompile.project.json"), StandardCharsets.UTF_8);
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
        assertTrue(manifest.contains("\"id\" : \"vlm-ocr-ingest\""));
        assertTrue(manifest.contains("\"id\" : \"run-vlm-ocr\""));

        assertTrue(pipeline.contains("\"enableVlm\" : true"));
        assertTrue(pipeline.contains("\"enableOcr\" : true"));
        assertTrue(pipeline.contains("\"pdfRoutingMode\" : \"FORCE_VLM\""));
        assertTrue(routing.contains("\"fileExtensions\":[\".pdf\"]"));
        assertTrue(routing.contains("\".png\""));
        assertTrue(stagingRegistry.contains("\"model_id\" : \"smoldocling-256m\""));
        assertTrue(stagingRegistry.contains("\"type\" : \"vlm_pipeline\""));
        assertTrue(stagingRegistry.contains("\"model_file\" : \"pipeline.json\""));
        assertTrue(stagingRegistry.contains("\"source_repository\" : \"ds4sd/SmolDocling-256M-preview\""));
        assertTrue(prompt.contains("Preserve table cells and reading order"));
        assertTrue(runbook.contains("kompile project workflow-run --root . --id vlm-ocr-ingest --dry-run"));
        assertTrue(runbook.contains("KOMPILE_BIN"));
        assertTrue(script.contains("--dry-run"));
        assertTrue(script.contains("KOMPILE_BIN=\"${KOMPILE_BIN:-kompile}\""));
        assertTrue(Files.isDirectory(projectRoot.resolve("data/ocr/vlm-ocr-docs")));
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
