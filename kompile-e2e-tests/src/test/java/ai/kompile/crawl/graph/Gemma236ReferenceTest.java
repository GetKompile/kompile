/* SPDX-License-Identifier: Apache-2.0 */
package ai.kompile.crawl.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Offline export of stored proc236 requests; never rebuilds an ontology prompt. */
class Gemma236ReferenceTest {
    private static String hash(String s) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void exportStoredRequestsWithoutWeights() throws Exception {
        String output = System.getProperty("gemma.236.export");
        assumeTrue(output != null);
        Path out = Path.of(output);
        assertFalse(Files.exists(out), "Exclusive export directory required");
        Path source = Path.of("target/gemma-eight-distinction-experiment");
        var mapper = new ObjectMapper();
        String manifestText = Files.readString(source.resolve("manifest-before-inference.json"));
        var manifest = mapper.readTree(manifestText);
        assertEquals(8, manifest.path("requests").size());
        assertEquals(8, manifest.path("frozenProbes").size());
        var settings = manifest.path("actualSettings");
        Path tokenizerPath = Path.of(settings.path("kompile.model.runtime.it.tokenizer").asText());
        String template = Files.readString(Path.of(settings.path("kompile.model.runtime.it.chatTemplateFile").asText()));
        assertEquals(manifest.path("priorRuntime").path("templateSha256").asText(), hash(template));
        assertEquals(manifest.path("priorRuntime").path("tokenizerSha256").asText(), hash(Files.readString(tokenizerPath)));
        String gguf = tokenizerPath.getParent().resolve("gemma-4-E2B-it-Q4_K_M.gguf").toString();
        try (var reader = new org.nd4j.ggml.format.GGUFReader(new java.io.File(gguf))) {
            assertEquals(template, reader.readHeader().getMetadata().get("tokenizer.chat_template"));
        }
        Files.createDirectories(out);
        try (var tokenizer = HuggingFaceTokenizer.fromFile(tokenizerPath.toFile())) {
            var method = GenerationPipeline.class.getDeclaredMethod("chatTemplateArguments", Map.class,
                    GenerationPipeline.ModelMetadata.class, org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer.class);
            method.setAccessible(true);
            for (int i = 0; i < 8; i++) {
                var stored = manifest.path("requests").get(i);
                String name = manifest.path("frozenProbes").get(i).path("id").asText();
                var messages = new ArrayList<ChatTemplate.Message>();
                for (var m : stored.path("messages")) messages.add(new ChatTemplate.Message(m.path("role").asText(), m.path("content").asText()));
                var tools = new ArrayList<ChatTemplate.Tool>();
                for (var t : stored.path("tools")) tools.add(ChatTemplate.Tool.function(t.path("name").asText(),
                        t.path("description").asText(), mapper.convertValue(t.path("parameters"), LinkedHashMap.class)));
                assertEquals("STANDARD", stored.path("toolDefinitionFormat").asText());
                assertEquals("MODEL", stored.path("toolCallFormat").asText());
                assertEquals("REQUIRED", stored.path("toolChoice").asText());
                @SuppressWarnings("unchecked") Map<String,Object> args = (Map<String,Object>) method.invoke(null,
                        mapper.convertValue(stored.path("templateArguments"), LinkedHashMap.class), GenerationPipeline.ModelMetadata.empty(), tokenizer);
                var request = ChatTemplate.Request.builder().messages(messages).tools(tools)
                        .addGenerationPrompt(stored.path("addGenerationPrompt").asBoolean())
                        .toolDefinitionFormat(ChatTemplate.ToolDefinitionFormat.STANDARD)
                        .toolCallFormat(ChatTemplate.ToolCallFormat.GEMMA).toolChoice(ChatTemplate.ToolChoice.REQUIRED)
                        .templateArguments(args).build();
                String rendered = tokenizer.applyChatTemplate(request, template);
                int[] ids = tokenizer.ensureLeadingBos(tokenizer.encode(rendered, false)).getIds();
                var context = mapper.readTree(ChatTemplate.requestContextJson(request));
                Files.writeString(out.resolve(name + ".context.json"), context.toPrettyString());
                var pb = new ProcessBuilder("python3", "src/test/python/gemma_template_reference.py",
                        "/home/agibsonccc/.kompile/reference-runners/llama-cpp-python-0.3.35")
                        .redirectOutput(out.resolve(name + ".python.json").toFile())
                        .redirectError(out.resolve(name + ".python.stderr").toFile());
                pb.environment().put("PYTHONNOUSERSITE", "1");
                var child = pb.start();
                try {
                    try (var stdin = child.getOutputStream()) { mapper.writeValue(stdin, Map.of("template", template, "context", context, "gguf", gguf)); }
                    assertTrue(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(0, child.exitValue());
                } finally { if (child.isAlive()) child.destroyForcibly(); }
                var reference = mapper.readTree(out.resolve(name + ".python.json").toFile());
                assertEquals(rendered, reference.path("rendered").asText(), name);
                assertArrayEquals(ids, mapper.treeToValue(reference.path("ids"), int[].class), name);
                String responseText = Files.readString(source.resolve(name + ".response.json"));
                String raw = mapper.readTree(responseText).path("response").path("rawText").asText();
                int[] responseIds = tokenizer.encode(raw, false).getIds();
                assertEquals(raw, tokenizer.decode(responseIds, false));
                Files.writeString(out.resolve(name + ".prompt.txt"), rendered);
                Files.writeString(out.resolve(name + ".ids.json"), Arrays.toString(ids));
                Files.writeString(out.resolve(name + ".response.txt"), raw);
                Files.writeString(out.resolve(name + ".response.ids.json"), Arrays.toString(responseIds));
                mapper.writeValue(out.resolve(name + ".provenance.json").toFile(), Map.of(
                        "manifestSha256", hash(manifestText), "responseSha256", hash(responseText), "templateSha256", hash(template),
                        "promptSha256", hash(rendered), "request", stored,
                        "limitation", "Response IDs are canonical retokenization of stored rawText, not captured generation IDs. No mask or sampling parity claimed."));
                System.out.println("FROZEN236_TEMPLATE_PARITY " + name + " tokens=" + ids.length + " responseTokens=" + responseIds.length + " sha256=" + hash(rendered));
            }
        }
        Files.writeString(out.resolve("weights-free-complete.json"), "{\"requests\":8,\"originalTemplateOnly\":true,\"weightsLoaded\":false}");
    }
}
