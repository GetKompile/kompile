package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.cli.main.chat.TestMilestonesCommand;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
class WebTestMilestonesTest {
    private static final String SESSION = "web-session";
    private static final String OTHER = "other-session";
    @TempDir Path project;

    @Test
    void defaultsToCurrentSessionAndCanSelectExplicitOrAllProjectSessions() throws Exception {
        write(project, "current1", SESSION, "harness", "core", 1);
        write(project, "other001", OTHER, "manual", "core", 2);
        write(project, "unstamp1", null, "manual", "legacy", 3);

        String current = completed("", SESSION).text();
        assertTrue(current.contains("Scope: session " + SESSION), current);
        assertTrue(current.contains("current1"), current);
        assertFalse(current.contains("other001"), current);
        assertFalse(current.contains("unstamp1"), current);
        assertTrue(current.contains("without a session stamp excluded"), current);
        String explicit = completed("session:" + OTHER, SESSION).text();
        assertTrue(explicit.contains("other001"), explicit);
        assertFalse(explicit.contains("current1"), explicit);
        String all = completed("all", null).text();
        assertTrue(all.contains("Scope: all sessions"), all);
        for (String id : List.of("current1", "other001", "unstamp1")) assertTrue(all.contains(id), all);
        assertTrue(all.contains("Coverage: 3 matched, 3 shown"), all);
    }

    @Test
    void usesSharedCountSourceAndModuleFiltersAndNewestFirstOrdering() throws Exception {
        write(project, "autoold1", SESSION, "harness", "core", 1);
        write(project, "autonew1", SESSION, "harness", "core", 2);
        write(project, "agent001", SESSION, "manual", "core", 3);
        write(project, "othermod", SESSION, "harness", "other", 4);

        String auto = completed("1 source:auto module:core", SESSION).text();
        assertTrue(auto.contains("Coverage: 2 matched, 1 shown (limit 1)"), auto);
        assertTrue(auto.contains("autonew1"), auto);
        for (String id : List.of("autoold1", "agent001", "othermod")) assertFalse(auto.contains(id), auto);
        String agent = completed("source:agent module:core", SESSION).text();
        assertTrue(agent.contains("agent001"), agent);
        assertFalse(agent.contains("autonew1"), agent);
        assertTrue(agent.contains("4/4 f0"), agent);
    }

    @Test
    void explicitHistoryCountHasNoOld100RowMaximum() {
        WebCommandResolver.Resolution report = completed("501", SESSION);
        assertTrue(report.text().contains("0 matched, 0 shown (limit 501)"), report.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2147483648", "9999999999999999999999999", "20 1", "1 2", "all all",
            "session:s all", "all session:s", "session:a session:b", "session:", "source:secret-value",
            "source:auto source:agent", "module:a module:b", "private-token", "path:/private-token",
            "--project=/private-token", "--global", "--all", "count:5"})
    void invalidArgumentsHaveTypedStatusAndDoNotReflectUnknownArgv(String arguments) throws Exception {
        Map<String, String> before = snapshot(project);
        WebCommandResolver.Resolution result = WebTestMilestones.resolve(arguments, SESSION, project);
        assertEquals(WebCommandResolver.Status.INVALID, result.status(), result.toString());
        assertEquals(2, result.exitCode());
        assertTrue(result.text().contains("Usage: /test-milestones"), result.text());
        assertFalse(result.text().contains("private-token"), result.text());
        assertFalse(result.text().contains("secret-value"), result.text());
        assertNull(result.modelPrompt());
        assertEquals(before, snapshot(project));
    }

    @Test
    void missingSessionIsInvalidButExplicitSessionAndHelpDoNotNeedCurrentSession() {
        WebCommandResolver.Resolution missing = WebTestMilestones.resolve("", null, project);
        assertEquals(WebCommandResolver.Status.INVALID, missing.status());
        assertTrue(missing.text().contains("No session id is available"), missing.text());
        completed("session:" + OTHER, null);
        for (String help : List.of("help", "-h", "--help")) {
            WebCommandResolver.Resolution result = completed(help, null);
            assertTrue(result.text().startsWith("Usage: /test-milestones"), result.text());
            assertFalse(result.text().contains("Error:"), result.text());
        }
    }

    @Test
    void neverLoadsGlobalRecordsEvenWhenTheirSessionMatchesOrAllIsRequested() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        write(home, "global01", SESSION, "harness", "private-global", 1);
        write(home, "global02", OTHER, "manual", "private-global", 2);
        write(home, "global03", null, "manual", "private-global", 3);
        write(project, "project1", SESSION, "harness", "public-project", 1);
        Map<String, String> beforeHome = snapshot(home);
        Map<String, String> beforeProject = snapshot(project);

        for (String args : List.of("", "session:" + OTHER, "all")) {
            String text = completed(args, SESSION).text();
            for (String privateValue : List.of("global01", "global02", "global03", "private-global",
                    "user global", "user-global")) assertFalse(text.contains(privateValue), text);
        }
        assertEquals(beforeHome, snapshot(home));
        assertEquals(beforeProject, snapshot(project));
    }

    @Test
    void missingStoresAndReportsNeverCreateFilesInProjectOrHome() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Map<String, String> beforeHome = snapshot(home);
        Map<String, String> beforeProject = snapshot(project);
        for (String args : List.of("", "all", "help", "session:" + OTHER)) {
            WebCommandResolver.Resolution result = completed(args, SESSION);
            if (!args.equals("help")) {
                assertTrue(result.text().contains("stores: none found"), result.text());
                assertTrue(result.text().contains("Coverage: 0 matched"), result.text());
            }
        }
        assertEquals(beforeHome, snapshot(home));
        assertEquals(beforeProject, snapshot(project));
        assertFalse(Files.exists(project.resolve(".kompile")));
    }

    @Test
    void malformedFilesAreDisclosedAndLegacyLayoutsKeepTheSharedFormatting() throws Exception {
        Path dir = milestones(project);
        Files.writeString(dir.resolve("broken.json"), "{not json");
        Files.writeString(dir.resolve("null.json"), "null");
        Files.writeString(dir.resolve("array.json"), "[]");
        Files.writeString(dir.resolve("legacy.json"), "{\"id\":\"legacy01\",\"sessionId\":\"" + SESSION
                + "\",\"executionId\":\"captured\",\"passing\":true,\"total\":4,\"passed\":4,\"failed\":0}");
        Map<String, String> before = snapshot(project);

        String text = completed("", SESSION).text();
        assertTrue(text.contains("legacy01"), text);
        assertTrue(text.contains("4/4 f0"), text);
        assertTrue(text.contains("PASS"), text);
        assertTrue(text.contains("unreadable milestone files skipped: 3"), text);
        assertEquals(TestMilestonesCommand.scopedReport(project, "", SESSION).text(), text);
        assertEquals(before, snapshot(project));
    }

    @ParameterizedTest
    @ValueSource(strings = {".kompile", ".kompile/test-milestones", ".kompile/test-milestones/milestones"})
    void refusesEveryStoreComponentThatSymlinksOutsideTheWorkspace(String relative, @TempDir Path outside)
            throws Exception {
        Path link = project.resolve(relative);
        Files.createDirectories(link.getParent());
        Path suffix = Path.of(relative).relativize(Path.of(".kompile/test-milestones/milestones"));
        Path targetStore = Files.createDirectories(outside.resolve(suffix));
        Files.writeString(targetStore.resolve("secret.json"), "{\"id\":\"secret01\",\"module\":\"private-data\"}");
        Files.createSymbolicLink(link, outside);
        Map<String, String> before = snapshot(project);
        Map<String, String> beforeOutside = snapshot(outside);

        assertSafeRefusal(WebTestMilestones.resolve("all", SESSION, project), outside);
        assertEquals(before, snapshot(project));
        assertEquals(beforeOutside, snapshot(outside));
    }

    @Test
    void incompleteLegacyObjectsWithNoIdsStillRenderWithoutSortFailures() throws Exception {
        Path dir = milestones(project);
        Files.writeString(dir.resolve("one.json"), "{}");
        Files.writeString(dir.resolve("two.json"), "{\"passing\":true}");
        assertTrue(completed("all", SESSION).text().contains("Coverage: 2 matched, 2 shown"));
    }

    @Test
    void rejectsEscapingAncestorEvenWhenItsMilestoneDirectoryDoesNotExist(@TempDir Path outside) throws Exception {
        Files.createSymbolicLink(project.resolve(".kompile"), outside);
        assertSafeRefusal(WebTestMilestones.resolve("all", SESSION, project), outside);
        assertFalse(Files.exists(outside.resolve("test-milestones")));
    }

    @Test
    void refusesSymlinkedMilestoneFilesOutsideTheWorkspace(@TempDir Path outside) throws Exception {
        Path secret = outside.resolve("secret.json");
        Files.writeString(secret, "{\"id\":\"secret01\",\"module\":\"private-data\"}");
        write(project, "allowed1", SESSION, "harness", "core", 1);
        Files.createSymbolicLink(milestones(project).resolve("escape.json"), secret);
        Map<String, String> before = snapshot(project);
        assertSafeRefusal(WebTestMilestones.resolve("all", SESSION, project), outside);
        assertEquals(before, snapshot(project));
    }

    @Test
    void allowsStoreAndFileSymlinksWhoseRealPathsStayInsideWorkspace() throws Exception {
        Path metadata = Files.createDirectories(project.resolve("metadata"));
        Files.createSymbolicLink(project.resolve(".kompile"), metadata);
        Path canonical = project.resolve("canonical.json");
        Files.writeString(canonical, "{\"id\":\"inside01\",\"sessionId\":\"" + SESSION + "\",\"passing\":true}");
        Files.createSymbolicLink(milestones(project).resolve("inside.json"), canonical);
        assertTrue(completed("", SESSION).text().contains("inside01"));
    }

    @Test
    void missingWorkspaceHasSanitizedErrorAndNullDirectoryNeverFallsBackToCwd() {
        Path missing = project.resolve("private-directory");
        assertSafeRefusal(WebTestMilestones.resolve("all", SESSION, missing), missing);
        assertEquals(WebCommandResolver.Status.INVALID, WebTestMilestones.resolve("all", SESSION, null).status());
        assertFalse(Files.exists(missing));
    }

    private WebCommandResolver.Resolution completed(String arguments, String session) {
        WebCommandResolver.Resolution result = WebTestMilestones.resolve(arguments, session, project);
        assertEquals(WebCommandResolver.Status.COMPLETED, result.status(), result.toString());
        assertEquals("/test-milestones", result.command());
        assertEquals(0, result.exitCode());
        assertTrue(result.isCommandOutcome());
        assertNull(result.modelPrompt());
        assertNull(result.data());
        return result;
    }

    private static void assertSafeRefusal(WebCommandResolver.Resolution result, Path outside) {
        assertEquals(WebCommandResolver.Status.INVALID, result.status(), result.toString());
        assertFalse(result.text().contains(outside.toString()), result.text());
        assertFalse(result.text().contains("secret01"), result.text());
        assertFalse(result.text().contains("private-data"), result.text());
        assertNull(result.modelPrompt());
    }

    private static Path milestones(Path base) throws Exception {
        return Files.createDirectories(TestMilestoneReader.storeDir(base).resolve(TestMilestoneReader.MILESTONES_DIR));
    }

    private static void write(Path base, String id, String session, String source, String module, int minute)
            throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        if (session != null) record.put("sessionId", session);
        record.put("source", source);
        record.put("module", module);
        record.put("timestamp", "2026-10-20T10:0" + minute + ":00Z");
        record.put("passing", true);
        record.put("totalTests", 4);
        record.put("passed", 4);
        record.put("failed", 0);
        Files.writeString(milestones(base).resolve(id + ".json"), JsonUtils.standardMapper().writeValueAsString(record));
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> entries = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                String value = attributes.lastModifiedTime().toString();
                if (attributes.isSymbolicLink()) value += ":link:" + Files.readSymbolicLink(path);
                else if (attributes.isRegularFile()) value += ":" + Files.readString(path);
                entries.put(root.relativize(path).toString(), value);
            }
        }
        return entries;
    }
}
