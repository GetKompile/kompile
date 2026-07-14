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

package ai.kompile.staging.subprocess;

import ai.kompile.staging.training.TranscriptJsonlDatasetSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.config.LoraConfig;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingSubprocessDatasetLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsDirectJsonlDataset() throws Exception {
        Path data = tempDir.resolve("sft.jsonl");
        Files.writeString(data,
                "{\"input\":\"Explain SameDiff arrays\",\"output\":\"Use INDArray tensors\",\"score\":0.75}\n" +
                "{\"prompt\":\"Train LoRA\",\"response\":\"Upload a dataset first\"}\n");

        Object dataset = resolveAndLoadDataset(data.toString());
        List<?> samples = samples(dataset);

        assertEquals(2, samples.size());
        assertEquals("Explain SameDiff arrays", sampleValue(samples.get(0), "inputValue"));
        assertEquals("Use INDArray tensors", sampleValue(samples.get(0), "labelValue"));
        assertEquals(0.75, ((Number) sampleValue(samples.get(0), "scoreValue")).doubleValue(), 1e-9);
    }

    @Test
    void preservesAndTensorizesPretokenizedJsonlFieldsByModelInputName() throws Exception {
        Path data = tempDir.resolve("tokenized.jsonl");
        Files.writeString(data,
                "{\"input_ids\":[11,12,13],\"attention_mask\":[1,1,1],\"labels\":[21,-100,23]}\n");

        Object dataset = resolveAndLoadDataset(data.toString());
        List<?> samples = samples(dataset);
        Object sample = samples.get(0);
        Map<?, ?> fields = (Map<?, ?>) sampleValue(sample, "fields");

        assertEquals(List.of(11, 12, 13), fields.get("input_ids"));
        assertEquals(List.of(1, 1, 1), fields.get("attention_mask"));
        assertEquals(List.of(21, -100, 23), fields.get("labels"));
        assertEquals(List.of(21, -100, 23), sampleValue(sample, "labelValue"));

        Method fillArray = TrainingSubprocessMain.class.getDeclaredMethod(
                "fillArray", INDArray.class, String.class, List.class, boolean.class);
        fillArray.setAccessible(true);
        INDArray inputIds = Nd4j.zeros(DataType.INT64, 1, 5);
        INDArray attentionMask = Nd4j.zeros(DataType.INT64, 1, 5);
        INDArray labels = Nd4j.zeros(DataType.INT64, 1, 3);
        try {
            fillArray.invoke(null, inputIds, "serving_default_input_ids:0", samples, true);
            fillArray.invoke(null, attentionMask, "attention_mask", samples, true);
            fillArray.invoke(null, labels, "training_labels", samples, false);
            assertEquals(11L, inputIds.getLong(0, 0));
            assertEquals(12L, inputIds.getLong(0, 1));
            assertEquals(13L, inputIds.getLong(0, 2));
            assertEquals(0L, inputIds.getLong(0, 3));
            assertEquals(0L, inputIds.getLong(0, 4));
            assertEquals(1L, attentionMask.getLong(0, 0));
            assertEquals(1L, attentionMask.getLong(0, 2));
            assertEquals(0L, attentionMask.getLong(0, 3));
            assertEquals(21L, labels.getLong(0, 0));
            assertEquals(-100L, labels.getLong(0, 1));
            assertEquals(23L, labels.getLong(0, 2));
        } finally {
            inputIds.close();
            attentionMask.close();
            labels.close();
        }
    }

    @Test
    void validatesDistillationWithIndependentlyShapedTeacherAndStudentJsonlInputs() throws Exception {
        Path data = tempDir.resolve("distillation-tokenized.jsonl");
        Files.writeString(data,
                "{\"teacher_features\":[1.0,2.0],\"student_features\":[3.0,4.0,5.0]}\n");
        TrainingSubprocessArgs args = TrainingSubprocessArgs.builder()
                .taskId("distill-shapes")
                .modelId("student")
                .datasetId(data.toString())
                .build();

        SameDiff teacher = SameDiff.create();
        SameDiff student = SameDiff.create();
        try {
            teacher.placeHolder("teacher_features", DataType.FLOAT, -1, 2)
                    .mmul(teacher.var("teacher_weight", Nd4j.ones(DataType.FLOAT, 2, 4)))
                    .rename("teacher_logits");
            student.placeHolder("student_features", DataType.FLOAT, -1, 3)
                    .mmul(student.var("student_weight", Nd4j.ones(DataType.FLOAT, 3, 4)))
                    .rename("student_logits");

            Method validate = TrainingSubprocessMain.class.getDeclaredMethod(
                    "validateDistillationCompatibility",
                    SameDiff.class, SameDiff.class, TrainingSubprocessArgs.class,
                    List.class, List.class, String.class, String.class,
                    TrainingSubprocessProgressReporter.class);
            validate.setAccessible(true);
            long[] shape = (long[]) validate.invoke(
                    null, teacher, student, args,
                    List.of("teacher_features"), List.of("student_features"),
                    "teacher_logits", "student_logits",
                    mock(TrainingSubprocessProgressReporter.class));

            assertArrayEquals(new long[]{1, 4}, shape);
        } finally {
            teacher.close();
            student.close();
        }
    }

    @Test
    void mapsNestedLoraRequestConfigurationToDl4jConfig() throws Exception {
        Map<String, Object> lora = Map.of(
                "rank", 4,
                "alpha", 12.0,
                "dropout", 0.1,
                "targetModules", List.of("q_proj", "v_proj"),
                "bias", "lora_only");
        LoraConfig config = buildLoraConfig(Map.of("peftType", "LORA", "loraConfig", lora));

        assertEquals(4, config.getR());
        assertEquals(12, config.getLoraAlpha());
        assertEquals(0.1, config.getLoraDropout(), 1e-12);
        assertEquals(List.of("q_proj", "v_proj"), config.getTargetModules());
        assertEquals("lora_only", config.getBias());
    }

    @Test
    void normalizesChatAndAlpacaJsonlRecords() throws Exception {
        Path data = tempDir.resolve("chat-sft.jsonl");
        Files.writeString(data,
                "{\"messages\":[{\"role\":\"system\",\"content\":\"Be concise\"},"
                        + "{\"role\":\"user\",\"content\":\"What is LoRA?\"},"
                        + "{\"role\":\"assistant\",\"content\":\"A low-rank adapter.\"}]}\n"
                        + "{\"instruction\":\"Summarize\",\"input\":\"SameDiff is a graph API\","
                        + "\"output\":\"SameDiff builds computation graphs.\"}\n");

        Object dataset = resolveAndLoadDataset(data.toString());
        List<?> samples = samples(dataset);

        assertEquals(2, samples.size());
        assertEquals("System: Be concise\nUser: What is LoRA?\nAssistant:",
                sampleValue(samples.get(0), "inputValue"));
        assertEquals("A low-rank adapter.", sampleValue(samples.get(0), "labelValue"));
        assertEquals("Summarize\n\nInput:\nSameDiff is a graph API",
                sampleValue(samples.get(1), "inputValue"));
        assertEquals("SameDiff builds computation graphs.", sampleValue(samples.get(1), "labelValue"));
    }

    @Test
    void reportsMalformedJsonlLineNumber() throws Exception {
        Path data = tempDir.resolve("broken.jsonl");
        Files.writeString(data, "{\"prompt\":\"valid\",\"completion\":\"row\"}\n{not-json}\n");

        Exception error = assertThrows(Exception.class, () -> resolveAndLoadDataset(data.toString()));
        String messages = messageChain(error);
        assertTrue(messages.contains(data + ":2"), messages);
    }

    @Test
    void detectsDirectClaudeCodeUuidJsonlDataset() throws Exception {
        Path data = tempDir.resolve("3e1c340d-4d42-4871-bc0c.jsonl");
        writeJsonLines(data, List.of(
                Map.of(
                        "uuid", "u1",
                        "type", "user",
                        "sessionId", "direct-session",
                        "message", Map.of("role", "user", "content", "Direct prompt")),
                Map.of(
                        "uuid", "a1",
                        "parentUuid", "u1",
                        "type", "assistant",
                        "sessionId", "direct-session",
                        "message", Map.of("role", "assistant", "content", "Direct response"))));

        Object dataset = resolveAndLoadDataset(data.toString());
        List<?> loadedSamples = samples(dataset);

        assertEquals(1, loadedSamples.size());
        assertEquals("User: Direct prompt\nAssistant:",
                sampleValue(loadedSamples.get(0), "inputValue"));
        assertEquals("Direct response", sampleValue(loadedSamples.get(0), "labelValue"));
    }

    @Test
    void loadsManagedClaudeCodeAndOpenCodeJsonlFormats() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            Path claudeDir = tempDir.resolve(".kompile/datasets/claude-training");
            Files.createDirectories(claudeDir);
            Path claudeData = claudeDir.resolve("history.jsonl");
            writeJsonLines(claudeData, List.of(
                    Map.of(
                            "type", "user",
                            "sessionId", "claude-session",
                            "message", Map.of("role", "user", "content", "Inspect the loader")),
                    Map.of(
                            "type", "assistant",
                            "sessionId", "claude-session",
                            "message", Map.of("role", "assistant", "content", "I will inspect it.")),
                    Map.of(
                            "type", "user",
                            "sessionId", "claude-session",
                            "message", Map.of(
                                    "role", "user",
                                    "content", List.of(Map.of(
                                            "type", "tool_result",
                                            "content", "loader source")))),
                    Map.of(
                            "type", "assistant",
                            "sessionId", "claude-session",
                            "message", Map.of("role", "assistant", "content", "The loader is fixed."))));
            writeManagedMeta(
                    claudeDir,
                    "claude-training",
                    TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL,
                    claudeData);

            Object claudeDataset = resolveAndLoadDataset("claude-training");
            List<?> claudeSamples = samples(claudeDataset);
            assertEquals(2, claudeSamples.size());
            assertEquals("User: Inspect the loader\nAssistant:",
                    sampleValue(claudeSamples.get(0), "inputValue"));
            assertEquals("I will inspect it.", sampleValue(claudeSamples.get(0), "labelValue"));
            assertTrue(String.valueOf(sampleValue(claudeSamples.get(1), "inputValue"))
                    .contains("Tool: [tool-result] loader source"));
            assertEquals("The loader is fixed.", sampleValue(claudeSamples.get(1), "labelValue"));

            Path openCodeDir = tempDir.resolve(".kompile/datasets/opencode-training");
            Files.createDirectories(openCodeDir);
            Path openCodeData = openCodeDir.resolve("history.jsonl");
            writeJsonLines(openCodeData, List.of(
                    Map.of(
                            "id", "m1",
                            "session_id", "open-session",
                            "type", "user",
                            "data", Map.of("text", "Run the focused tests")),
                    Map.of(
                            "id", "m2",
                            "session_id", "open-session",
                            "type", "assistant",
                            "data", Map.of("content", List.of(
                                    Map.of("type", "reasoning", "text", "Use Maven."),
                                    Map.of("type", "text", "text", "All tests pass."))))));
            writeManagedMeta(
                    openCodeDir,
                    "opencode-training",
                    TranscriptJsonlDatasetSupport.OPENCODE_JSONL,
                    openCodeData);

            Object openCodeDataset = resolveAndLoadDataset("opencode-training");
            List<?> openCodeSamples = samples(openCodeDataset);
            assertEquals(1, openCodeSamples.size());
            assertEquals("User: Run the focused tests\nAssistant:",
                    sampleValue(openCodeSamples.get(0), "inputValue"));
            assertEquals("[thinking] Use Maven.\nAll tests pass.",
                    sampleValue(openCodeSamples.get(0), "labelValue"));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void loadsManagedDatasetMetadataAndCsvColumnMappings() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path datasetDir = tempDir.resolve(".kompile/datasets/managed-ds");
            Files.createDirectories(datasetDir);
            Path data = datasetDir.resolve("data.csv");
            Files.writeString(data,
                    "prompt,answer,chosen,rejected,reward\n" +
                    "What is SDX?,A runtime bundle,Good answer,Bad answer,1.0\n");
            Files.writeString(datasetDir.resolve("meta.json"),
                    "{\n" +
                    "  \"id\": \"managed-ds\",\n" +
                    "  \"format\": \"csv\",\n" +
                    "  \"task\": \"alignment\",\n" +
                    "  \"filePath\": \"" + data.toString().replace("\\", "\\\\") + "\",\n" +
                    "  \"inputColumn\": \"prompt\",\n" +
                    "  \"outputColumn\": \"answer\",\n" +
                    "  \"chosenColumn\": \"chosen\",\n" +
                    "  \"rejectedColumn\": \"rejected\"\n" +
                    "}\n");

            Object dataset = resolveAndLoadDataset("managed-ds");
            List<?> samples = samples(dataset);

            assertEquals(1, samples.size());
            assertEquals("What is SDX?", sampleValue(samples.get(0), "inputValue"));
            assertEquals("A runtime bundle", sampleValue(samples.get(0), "labelValue"));
            assertEquals("Good answer", sampleValue(samples.get(0), "chosenValue"));
            assertEquals("Bad answer", sampleValue(samples.get(0), "rejectedValue"));
            assertNotNull(sampleValue(samples.get(0), "scoreValue"));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void writesDeployableTrainingArtifactManifest() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path datasetDir = tempDir.resolve(".kompile/datasets/manifest-ds");
            Files.createDirectories(datasetDir);
            Path data = datasetDir.resolve("data.jsonl");
            Files.writeString(data, "{\"input\":\"hi\",\"output\":\"there\"}\n");
            Files.writeString(datasetDir.resolve("meta.json"),
                    "{\"id\":\"manifest-ds\",\"format\":\"jsonl\",\"task\":\"sft\",\"filePath\":\""
                            + data.toString().replace("\\", "\\\\") + "\"}\n");

            Path outputDir = tempDir.resolve("training-output");
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("model.fb"), "sameDiff-flatbuffer-placeholder");

            TrainingSubprocessArgs args = TrainingSubprocessArgs.builder()
                    .taskId("train-sub-9")
                    .trainingType("LORA")
                    .modelId("Qwen/Base")
                    .datasetId("manifest-ds")
                    .epochs(2)
                    .batchSize(4)
                    .learningRate(2.0e-4)
                    .peftConfigJson("{\"peftType\":\"LORA\",\"rank\":8}")
                    .build();

            Path manifest = writeTrainingArtifactManifest(args, "lora", outputDir.toString(),
                    "model.fb", Map.of("final_train_loss", 0.25), Map.<String, Object>of("rank", 8));
            Map<?, ?> json = new ObjectMapper().readValue(manifest.toFile(), Map.class);
            Map<?, ?> dataset = (Map<?, ?>) json.get("dataset");
            Map<?, ?> registry = (Map<?, ?>) json.get("registrySuggestion");
            List<?> artifacts = (List<?>) json.get("artifacts");

            assertEquals("kompile.training-artifact.v1", json.get("schemaVersion"));
            assertEquals("qwen-base-lora-train-sub-9", json.get("trainedModelId"));
            assertEquals("LORA", json.get("trainingType"));
            assertEquals(Boolean.TRUE, json.get("deployable"));
            assertEquals("manifest-ds", dataset.get("datasetId"));
            assertEquals(1, dataset.get("sampleCount"));
            assertEquals("STAGED", registry.get("status"));
            assertEquals("model.fb", ((Map<?, ?>) artifacts.get(0)).get("relativePath"));
            assertTrue(Files.isRegularFile(manifest));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private static void writeJsonLines(Path path, List<? extends Map<String, ?>> rows) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder content = new StringBuilder();
        for (Map<String, ?> row : rows) {
            content.append(mapper.writeValueAsString(row)).append(System.lineSeparator());
        }
        Files.writeString(path, content);
    }

    private static void writeManagedMeta(
            Path datasetDir, String id, String format, Path dataFile) throws Exception {
        Map<String, Object> meta = Map.of(
                "id", id,
                "format", format,
                "task", "sft",
                "filePath", dataFile.toString(),
                "inputColumn", "text",
                "outputColumn", "");
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(datasetDir.resolve("meta.json").toFile(), meta);
    }

    private static LoraConfig buildLoraConfig(Map<String, Object> config) throws Exception {
        Method method = TrainingSubprocessMain.class.getDeclaredMethod("buildLoraConfig", Map.class);
        method.setAccessible(true);
        return (LoraConfig) method.invoke(null, config);
    }

    @SuppressWarnings("unchecked")
    private static Path writeTrainingArtifactManifest(TrainingSubprocessArgs args,
                                                      String processType,
                                                      String outputPath,
                                                      String modelFileName,
                                                      Map<String, Double> finalMetrics,
                                                      Map<String, Object> processConfig) throws Exception {
        Method method = TrainingSubprocessMain.class.getDeclaredMethod("writeTrainingArtifactManifest",
                TrainingSubprocessArgs.class, String.class, String.class, String.class, Map.class, Map.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, args, processType, outputPath, modelFileName, finalMetrics, processConfig);
    }

    private static String messageChain(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                if (messages.length() > 0) messages.append(" | ");
                messages.append(current.getMessage());
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static Object resolveAndLoadDataset(String datasetId) throws Exception {
        Method method = TrainingSubprocessMain.class.getDeclaredMethod("resolveAndLoadDataset", String.class);
        method.setAccessible(true);
        return method.invoke(null, datasetId);
    }

    @SuppressWarnings("unchecked")
    private static List<?> samples(Object dataset) throws Exception {
        Method method = dataset.getClass().getDeclaredMethod("samples");
        method.setAccessible(true);
        return (List<?>) method.invoke(dataset);
    }

    private static Object sampleValue(Object sample, String accessor) throws Exception {
        Method method = sample.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(sample);
    }
}
