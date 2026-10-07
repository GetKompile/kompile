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

package ai.kompile.app.services.agent;

import ai.kompile.cli.common.WebChatContext;
import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightSource;
import ai.kompile.cli.insights.Insights;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatInsightsServiceTest {

    /** A Saturday noon; the judge's default window starts on Saturday 09-26. */
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> PROPERTIES =
            List.of("user.home", WebChatContext.WORKING_DIRECTORY, WebChatContext.MODE);

    @TempDir
    Path tempDir;

    private final Map<String, String> previous = new HashMap<>();
    private Path home;
    private Path project;
    private Path configFile;
    private ChatHarnessClient harness;

    @BeforeEach
    void setUp() throws IOException {
        PROPERTIES.forEach(key -> previous.put(key, System.getProperty(key)));
        home = Files.createDirectories(tempDir.resolve("home"));
        project = Files.createDirectories(tempDir.resolve("project"));
        configFile = tempDir.resolve("config").resolve(InsightsConfig.FILE_NAME);
        System.setProperty("user.home", home.toString());
        // The chat is bound to the project, as `kompile chat --web` binds it.
        System.setProperty(WebChatContext.WORKING_DIRECTORY, project.toString());
        System.clearProperty(WebChatContext.MODE);
        harness = mock(ChatHarnessClient.class);
    }

    @AfterEach
    void tearDown() {
        previous.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }

    private ChatInsightsService service() {
        return service(ChatInsightsService::localInsights);
    }

    private ChatInsightsService service(BiFunction<Path, InsightsConfig, Insights> localInsights) {
        return new ChatInsightsService(harness, localInsights, () -> configFile, CLOCK);
    }

    private static void write(Path file, String singleQuoted) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, singleQuoted.replace('\'', '"'), StandardCharsets.UTF_8);
    }

    /** Session s1's judge log, where the enforcer writes it: two verdicts this morning, one flagged and stopped. */
    private void judgeLog() throws IOException {
        Path log = home.resolve(".kompile/sessions/s1/judgements.jsonl");
        write(log, "{'timestamp':'2026-10-03T09:00:00Z','phase':'JUDGE_TOOL','judgeMode':'llm','compliant':true,"
                + "'stop':false,'toolName':'Bash','latencyMs':1200}\n"
                + "{'timestamp':'2026-10-03T09:05:00Z','phase':'JUDGE_TOOL','judgeMode':'llm','compliant':false,"
                + "'stop':true,'severity':'HIGH','toolName':'Bash','latencyMs':2400,"
                + "'violations':['Used cat instead of read']}\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.parse("2026-10-03T09:10:00Z")));
    }

    private static JsonNode unavailable(String topic, String status) {
        return MAPPER.createObjectNode().put("menu", "insights").put("topic", topic).put("available", false)
                .put("status", status);
    }

    @Test
    void judgeToolsAndTestsAreReadHereFromTheFilesTheCliWrites() throws IOException {
        judgeLog();
        write(home.resolve(".kompile/conversations/tool-calls/all-tool-calls.jsonl"),
                "{'timestamp':'2026-10-03T09:00:00Z','toolName':'grep','source':'mcp-stdio','sessionId':'s1',"
                        + "'toolInputSummary':'pattern=insights','durationMs':40}\n");
        // The project's own runs, and the class results the user's test sweeps record at home.
        write(project.resolve(".kompile/test-milestones/milestones/0a1b2c3d.json"),
                "{'id':'0a1b2c3d','module':'web-chat-fixture','timestamp':'2026-10-01T10:00:00Z','passing':true,"
                        + "'totalTests':12,'passed':12}");
        write(home.resolve(".kompile/test-milestones/milestones/HomeFixtureTest.json"),
                "{'id':'HomeFixtureTest','module':'home-fixture','timestamp':'2026-10-02T11:00:00Z',"
                        + "'passing':true,'total':3,'passed':3}");
        ChatInsightsService service = service();

        JsonNode judge = service.report("judge", null, null);
        assertEquals("insights", judge.path("menu").asText());
        assertEquals("judge", judge.path("topic").asText());
        assertTrue(judge.path("available").asBoolean(), judge.toString());
        assertEquals("Judge, last 7 days: 2 verdicts in 1 session, 1 flagged (50.0%), 1 stop verdict",
                judge.path("headline").asText());
        assertTrue(judge.path("text").asText().contains("Used cat instead of read"), judge.path("text").asText());
        assertEquals("bar", judge.path("chart").path("kind").asText());

        JsonNode tests = service.report(" Tests ", null, null);
        assertEquals("tests", tests.path("topic").asText());
        assertEquals("Tests, all time: 1 run of 1 module, 100.0% green; every module passed its latest run; "
                + "1 test class, 1 passing", tests.path("headline").asText());
        String text = tests.path("text").asText();
        assertTrue(text.contains("web-chat-fixture") && text.contains("home-fixture"), text);

        JsonNode tools = service.report("tools", "slowest calls", null);
        assertEquals("tools", tools.path("topic").asText());
        assertTrue(tools.path("available").asBoolean(), tools.toString());
        assertTrue(tools.path("text").asText().contains("grep"), tools.path("text").asText());
        verifyNoInteractions(harness);
    }

    @Test
    void tokenDrillDownPreservesStructuredUsageAndMeasurementProvenance() throws IOException {
        write(home.resolve(".kompile/conversations/tool-calls/tool-usage.jsonl"),
                "{'recordType':'usage','revision':1,'invocationId':'invocation-full',"
                        + "'sessionId':'s1','requestedToolName':'read','resolvedToolName':'read',"
                        + "'startedEpochMs':" + Instant.parse("2026-09-15T09:00:00Z").toEpochMilli() + ","
                        + "'durationMs':40,'outcome':'EXECUTED','disposition':'DELIVERED',"
                        + "'arguments':{'status':'MEASURED','tokens':2,'tokenizerId':'fixture'},"
                        + "'payload':{'status':'MEASURED','tokens':9,'tokenizerId':'fixture'},"
                        + "'modelExecutions':[]}\n");
        JsonNode report = service().report("tools", "tokens session:s1 call:invocation-full last 30 days", null);
        assertTrue(report.path("available").asBoolean(), report.toString());
        JsonNode usage = report.path("usage");
        assertEquals(1, usage.path("summary").path("calls").asInt(), report.toString());
        assertEquals(9, usage.path("summary").path("payloadTokens").asInt());
        assertEquals("invocation-full", usage.path("calls").get(0).path("invocationId").asText());
        assertEquals("MEASURED", usage.path("calls").get(0).path("payload").path("status").asText());
        assertEquals("fixture", usage.path("calls").get(0).path("payload").path("tokenizerId").asText());
        assertEquals("line", report.path("chart").path("kind").asText());
        JsonNode shorter = service().report("tools", "tokens session:s1 call:invocation-full last 7 days", null);
        assertEquals(0, shorter.path("usage").path("summary").path("calls").asInt());
        verifyNoInteractions(harness);
    }

    @Test
    void crawlsGraphsAndTheOverviewComeFromTheHarness() {
        ObjectNode answer = MAPPER.createObjectNode().put("menu", "insights").put("topic", "crawl");
        when(harness.insightsReport(any(), any(), any())).thenReturn(answer);
        ChatInsightsService service = service();

        assertSame(answer, service.report("crawl", " failed crawls ", "/x"));
        verify(harness).insightsReport("crawl", "failed crawls", "/x");
        service.report(null, null, null);
        verify(harness).insightsReport("overview", null, null);
        service.report(" Graph ", "  ", null);
        verify(harness).insightsReport("graph", null, null);
        service.report("crawl", "q".repeat(1_000), null);
        verify(harness).insightsReport("crawl", "q".repeat(1_000), null);

        // The harness knows the topics only it reads, and names them.
        when(harness.insightsReport(eq("crawls"), any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown insights topic 'crawls'. Topics: crawl, graph"));
        assertEquals("Unknown insights topic 'crawls'. Topics: crawl, graph",
                assertThrows(IllegalArgumentException.class, () -> service.report("crawls", null, null)).getMessage());
    }

    @Test
    void withoutAHarnessOnlyTheTopicsReadHereAnswer() throws IOException {
        judgeLog();
        ChatInsightsService service =
                new ChatInsightsService(null, ChatInsightsService::localInsights, () -> configFile, CLOCK);

        assertTrue(service.report("judge", null, null).path("available").asBoolean());
        assertEquals("Kompile CLI harness is unavailable",
                assertThrows(IllegalStateException.class, () -> service.report("graph", null, null)).getMessage());
    }

    @Test
    void anInvalidTopicOrQuestionIsRejectedBeforeAnythingIsRead() {
        ChatInsightsService service = service();

        for (String topic : List.of("cr awl", "1judge", "../judge", "judge;ls", "t".repeat(65))) {
            assertEquals("Invalid insights topic", assertThrows(IllegalArgumentException.class,
                    () -> service.report(topic, null, null), topic).getMessage());
        }
        for (String question : List.of("q".repeat(1_001), "failed\ncrawls")) {
            assertEquals("Invalid insights question", assertThrows(IllegalArgumentException.class,
                    () -> service.report("judge", question, null)).getMessage());
        }
        verifyNoInteractions(harness);
    }

    @Test
    void aDirectoryOtherThanTheChatsIsUnavailable() throws IOException {
        ChatInsightsService service = service();

        assertEquals(unavailable("judge", "This CLI web chat is bound to: " + project.toRealPath()),
                service.report("judge", null, tempDir.toString()));
        Path missing = tempDir.resolve("missing");
        assertEquals(unavailable("tests", "Chat working directory does not exist: " + missing),
                service.report("tests", null, missing.toString()));
        verifyNoInteractions(harness);
    }

    @Test
    void sourcesReadTheChatsProjectAndDataThatCannotBeReadIsUnavailable() throws IOException {
        AtomicReference<Path> read = new AtomicReference<>();
        ChatInsightsService failing = service((workDir, config) -> {
            read.set(workDir);
            return new Insights().register(new Failing("judge", new IOException("disk gone")));
        });

        assertEquals(unavailable("judge", "Could not read insights data: disk gone"),
                failing.report("judge", null, null));
        assertEquals(project.toRealPath(), read.get());
        ChatInsightsService silent = service((workDir, config) ->
                new Insights().register(new Failing("judge", new IOException())));
        assertEquals(unavailable("judge", "Could not read insights data: IOException"),
                silent.report("judge", null, null));
    }

    @Test
    void insightsJsonLimitsTheReportsAndThePageEditsIt() throws IOException {
        judgeLog();
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "[]");
        ChatInsightsService service = service();
        String warning = configFile + " is not a JSON object; using defaults";

        JsonNode settings = service.settings();
        assertEquals(configFile.toAbsolutePath().toString(), settings.path("file").asText());
        assertEquals(InsightsConfig.defaults().toJson(), settings.path("settings"));
        assertEquals(InsightsConfig.defaults().toJson(), settings.path("defaults"));
        assertEquals(warning, settings.path("warning").asText());
        // A report says why its limits are the defaults.
        assertTrue(service.report("judge", null, null).path("text").asText().contains(warning));

        JsonNode saved = service.saveSettings(MAPPER.readTree("{\"defaultWindowDays\":30,\"maxRows\":3}"));
        assertEquals(30, saved.path("settings").path("defaultWindowDays").asInt());
        assertEquals(3, saved.path("settings").path("maxRows").asInt());
        assertEquals(10, saved.path("defaults").path("maxRows").asInt());
        assertFalse(saved.has("warning"), saved.toString());
        assertEquals(saved, service.settings());
        assertEquals(30, InsightsConfig.load(configFile).getDefaultWindowDays());
        assertTrue(service.report("judge", null, null).path("headline").asText()
                .startsWith("Judge, last 30 days: 2 verdicts"));

        // An invalid change is named, and the file keeps its settings.
        String before = Files.readString(configFile);
        assertEquals("maxRows must be a positive whole number", assertThrows(IllegalArgumentException.class,
                () -> service.saveSettings(MAPPER.readTree("{\"maxRows\":0}"))).getMessage());
        assertEquals("Unknown insights setting 'colour'", assertThrows(IllegalArgumentException.class,
                () -> service.saveSettings(MAPPER.readTree("{\"colour\":\"red\"}"))).getMessage());
        assertEquals("Insights settings must be a JSON object", assertThrows(IllegalArgumentException.class,
                () -> service.saveSettings(null)).getMessage());
        assertEquals(before, Files.readString(configFile));
    }

    /** A source whose data cannot be read. */
    private record Failing(String topic, IOException failure) implements InsightSource {

        @Override
        public String description() {
            return topic;
        }

        @Override
        public List<String> keywords() {
            return List.of(topic);
        }

        @Override
        public InsightReport report(InsightsQuery query) throws IOException {
            throw failure;
        }
    }
}
