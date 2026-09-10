package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.config.ImportMode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudSettingsBundleServiceTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    @Test
    void capturesUserAndProjectSettingsWhileRemovingPlaintextSecrets() throws Exception {
        Path userHome = tempDir.resolve("home/.kompile");
        Path project = tempDir.resolve("project");
        write(userHome.resolve("config/app-index-config.json"),
                "{\"theme\":\"dark\",\"apiKey\":\"raw-key\","
                        + "\"database\":{\"pgvectorPassword\":\"raw-password\",\"host\":\"db\"},"
                        + "\"headers\":{\"Cookie\":\"session=raw\"},"
                        + "\"databaseUrl\":\"postgres://alice:secret@db.example/app\"}");
        write(userHome.resolve("chat-config.json"),
                "{\"provider\":\"openai\",\"token\":\"${OPENAI_TOKEN}\"}");
        write(userHome.resolve("auth.json"), "{\"token\":\"must-never-upload\"}");
        write(project.resolve("config/pipeline-config.json"), "{\"maxBatchSize\":32}");
        write(project.resolve(".kompile/enforcer-config.json"),
                "{\"keywordMode\":\"warn\",\"judgeApiKey\":\"judge-secret\"}");
        write(project.resolve(".kompile/registration.json"), "{\"id\":\"runtime-state\"}");

        CloudSettingsBundleService service = new CloudSettingsBundleService(userHome);
        CloudSettingsBundleService.CaptureResult result = service.capture(
                CloudSettingsBundleService.Scope.ALL, project);
        ObjectNode bundle = result.getBundle();

        assertEquals(4, result.getFiles().size());
        assertTrue(result.getFiles().contains("user/config/app-index-config.json"));
        assertTrue(result.getFiles().contains("user/chat-config.json"));
        assertTrue(result.getFiles().contains("project/config/pipeline-config.json"));
        assertTrue(result.getFiles().contains("project/.kompile/enforcer-config.json"));
        assertFalse(result.getFiles().stream().anyMatch(path -> path.contains("auth.json")));
        assertFalse(result.getFiles().stream().anyMatch(path -> path.contains("registration.json")));

        JsonNode app = bundle.path("files").path("user/config/app-index-config.json");
        assertEquals("dark", app.path("theme").asText());
        assertFalse(app.has("apiKey"));
        assertFalse(app.path("database").has("pgvectorPassword"));
        assertEquals("db", app.path("database").path("host").asText());
        assertFalse(app.path("headers").has("Cookie"));
        assertFalse(app.has("databaseUrl"));
        assertEquals("${OPENAI_TOKEN}", bundle.path("files").path("user/chat-config.json")
                .path("token").asText(), "Environment references are portable, not secrets");
        assertEquals(5, result.getRedactedPaths().size());
        service.validateBundle(bundle);
    }

    @Test
    void appendAndOverrideAlwaysPreserveLocalSecrets() throws Exception {
        Path userHome = tempDir.resolve("home/.kompile");
        Path project = tempDir.resolve("project");
        Path target = userHome.resolve("config/app-index-config.json");
        write(target, "{\"theme\":\"local\",\"apiKey\":\"local-secret\","
                + "\"database\":{\"password\":\"db-secret\",\"localOnly\":true},"
                + "\"provider\":{\"apiKey\":\"nested-local-secret\"}}");

        ObjectNode bundle = bundle();
        ObjectNode remote = bundle.with("files").putObject("user/config/app-index-config.json");
        remote.put("theme", "cloud");
        remote.putObject("database").put("host", "cloud-db");
        bundle.with("files").putObject("project/config/pipeline-config.json")
                .put("maxBatchSize", 64);

        CloudSettingsBundleService service = new CloudSettingsBundleService(userHome);
        CloudSettingsBundleService.ApplyResult appended =
                service.apply(bundle, ImportMode.APPEND, project);
        JsonNode afterAppend = MAPPER.readTree(target.toFile());
        assertEquals("cloud", afterAppend.path("theme").asText());
        assertEquals("local-secret", afterAppend.path("apiKey").asText());
        assertEquals("db-secret", afterAppend.path("database").path("password").asText());
        assertTrue(afterAppend.path("database").path("localOnly").asBoolean());
        assertEquals("cloud-db", afterAppend.path("database").path("host").asText());
        assertEquals(1, appended.getCreated().size());
        assertEquals(1, appended.getUpdated().size());

        service.apply(bundle, ImportMode.OVERRIDE, project);
        JsonNode afterOverride = MAPPER.readTree(target.toFile());
        assertEquals("cloud", afterOverride.path("theme").asText());
        assertEquals("local-secret", afterOverride.path("apiKey").asText());
        assertEquals("db-secret", afterOverride.path("database").path("password").asText());
        assertEquals("nested-local-secret",
                afterOverride.path("provider").path("apiKey").asText());
        assertFalse(afterOverride.path("database").has("localOnly"));
        assertEquals(64, MAPPER.readTree(project.resolve("config/pipeline-config.json").toFile())
                .path("maxBatchSize").asInt());
    }

    @Test
    void rejectsTraversalUnknownFilesAndPlaintextSecretsBeforeWriting() {
        Path userHome = tempDir.resolve("home/.kompile");
        CloudSettingsBundleService service = new CloudSettingsBundleService(userHome);

        ObjectNode traversal = bundle();
        traversal.with("files").putObject("project/config/../auth.json").put("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> service.validateBundle(traversal));

        ObjectNode forbidden = bundle();
        forbidden.with("files").putObject("user/auth.json").put("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> service.validateBundle(forbidden));

        ObjectNode secret = bundle();
        secret.with("files").putObject("user/config/llm-provider-config.json")
                .put("apiKey", "plaintext");
        assertThrows(IllegalArgumentException.class, () -> service.validateBundle(secret));

        ObjectNode unexpected = bundle();
        unexpected.put("apiKey", "outside-files");
        assertThrows(IllegalArgumentException.class, () -> service.validateBundle(unexpected));

        ObjectNode cookie = bundle();
        cookie.with("files").putObject("user/config/app-index-config.json")
                .putObject("headers").put("Proxy-Authorization", "Bearer secret");
        assertThrows(IllegalArgumentException.class, () -> service.validateBundle(cookie));

        ObjectNode credentialUrl = bundle();
        credentialUrl.with("files").putObject("user/config/app-index-config.json")
                .put("databaseUrl", "jdbc:postgresql://alice:password@db.example/app");
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(credentialUrl));

        ObjectNode canonicalAuth = bundle();
        canonicalAuth.with("files").putObject("user/config/app-index-config.json")
                .putObject("auth").put("type", "api_key").put("key", "sk-live");
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(canonicalAuth));

        ObjectNode accessTokenUrl = bundle();
        accessTokenUrl.with("files").putObject("user/config/app-index-config.json")
                .put("sourceUrl", "https://host/data?access_token=live-secret");
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(accessTokenUrl));

        ObjectNode authorizationValue = bundle();
        authorizationValue.with("files").putObject("user/config/app-index-config.json")
                .putArray("values").add("Authorization: Bearer live-secret");
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(authorizationValue));

        ObjectNode oracleJdbc = bundle();
        oracleJdbc.with("files").putObject("user/config/app-index-config.json")
                .put("sourceUrl", "jdbc:oracle:thin:user/password@host");
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(oracleJdbc));

        ObjectNode caseInsensitiveForbidden = bundle();
        caseInsensitiveForbidden.with("files").putObject("user/config/Auth.json")
                .put("enabled", true);
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(caseInsensitiveForbidden));

        ObjectNode collision = bundle();
        collision.with("files").putObject("user/config/A.json").put("a", true);
        collision.with("files").putObject("user/config/a.json").put("a", false);
        assertThrows(IllegalArgumentException.class,
                () -> service.validateBundle(collision));

        ObjectNode fractional = bundle();
        fractional.put("formatVersion", 1.9);
        assertThrows(IllegalArgumentException.class, () -> service.validateBundle(fractional));
    }

    @Test
    void rejectsSymlinkedProjectParentsAndSecretParentTypeConflicts() throws Exception {
        Path userHome = tempDir.resolve("home/.kompile");
        Path project = tempDir.resolve("project");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(project);
        Files.createDirectories(outside.resolve("config"));
        write(outside.resolve("config/agent-defaults.json"), "{\"model\":\"outside\"}");
        try {
            Files.createSymbolicLink(project.resolve(".kompile"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        CloudSettingsBundleService service = new CloudSettingsBundleService(userHome);
        assertThrows(java.io.IOException.class, () -> service.capture(
                CloudSettingsBundleService.Scope.PROJECT, project));

        Files.delete(project.resolve(".kompile"));
        Path target = userHome.resolve("config/app-index-config.json");
        write(target, "{\"provider\":{\"apiKey\":\"local-secret\"}}");
        ObjectNode conflict = bundle();
        conflict.with("files").putObject("user/config/app-index-config.json")
                .put("provider", "cloud-scalar");
        assertThrows(IllegalArgumentException.class,
                () -> service.apply(conflict, ImportMode.OVERRIDE, project));
        assertEquals("local-secret", MAPPER.readTree(target.toFile())
                .path("provider").path("apiKey").asText());
    }

    @Test
    void boundsLocalFilesAndDepthBeforeBuildingTheBundle() throws Exception {
        Path userHome = tempDir.resolve("home/.kompile");
        Path config = userHome.resolve("config/app-index-config.json");
        write(config, "{\"value\":\"" + "x".repeat(1_048_577) + "\"}");
        CloudSettingsBundleService service = new CloudSettingsBundleService(userHome);
        assertThrows(java.io.IOException.class,
                () -> service.capture(CloudSettingsBundleService.Scope.USER, null));

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 34; i++) {
            deep.append("{\"n\":");
        }
        deep.append("true");
        for (int i = 0; i < 34; i++) {
            deep.append('}');
        }
        write(config, deep.toString());
        assertThrows(java.io.IOException.class,
                () -> service.capture(CloudSettingsBundleService.Scope.USER, null));
    }

    @Test
    void abortsAndRollsBackWithoutOverwritingAConcurrentLocalEdit() throws Exception {
        Path userHome = tempDir.resolve("home/.kompile");
        Path first = userHome.resolve("config/a.json");
        Path second = userHome.resolve("config/b.json");
        write(first, "{\"theme\":\"first-original\"}");
        write(second, "{\"theme\":\"second-original\",\"apiKey\":\"old-secret\"}");

        ObjectNode cloud = bundle();
        cloud.with("files").putObject("user/config/a.json").put("theme", "cloud-a");
        cloud.with("files").putObject("user/config/b.json").put("theme", "cloud-b");

        CloudSettingsBundleService service = new CloudSettingsBundleService(userHome, target -> {
            if (target.equals(second)) {
                write(second, "{\"theme\":\"external-edit\",\"apiKey\":\"new-secret\"}");
            }
        });

        assertThrows(java.io.IOException.class,
                () -> service.apply(cloud, ImportMode.OVERRIDE, tempDir.resolve("project")));
        assertEquals("first-original", MAPPER.readTree(first.toFile()).path("theme").asText());
        JsonNode concurrent = MAPPER.readTree(second.toFile());
        assertEquals("external-edit", concurrent.path("theme").asText());
        assertEquals("new-secret", concurrent.path("apiKey").asText());
    }

    private static ObjectNode bundle() {
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("formatVersion", 1);
        bundle.putObject("files");
        return bundle;
    }

    private static void write(Path path, String content) throws java.io.IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
