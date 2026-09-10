package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePolicyTest {
    @TempDir Path root;

    static ObjectNode smallTestPolicy() throws Exception {
        return (ObjectNode) JsonUtils.standardMapper().readTree("""
                {"defaultClass":"high","unknownShellClass":"high","rules":[
                  {"id":"small-test","tool":"bash","class":"low","all":[
                    {"path":"/shell/executable","op":"eq","value":"mvn"},
                    {"path":"/shell/argv","op":"contains","value":"test"},
                    {"path":"/shell/options/-Dtest","op":"eq","value":"SmallTest"}
                  ]}
                ]}
                """);
    }

    @Test void parsesExecutableFlagsAndQuotedValuesWithoutSubstringMatching() throws Exception {
        var policy = smallTestPolicy();
        assertFalse(decide(policy, "/home/agibsonccc/dev-apps/mvn/bin/mvn test -Dtest='SmallTest'").high());
        assertTrue(decide(policy, "mvn test -Dtest=SmallTestExtra").high());
        assertTrue(decide(policy, "printf 'mvn test -Dtest=SmallTest'").high());
        assertTrue(decide(policy, "mvn package -Dtest=SmallTest").high());
        assertFalse(decide(policy, "MAVEN_OPTS='-Xmx256m' mvn test -Dtest=SmallTest").high());
        assertFalse(decide(policy, "env MAVEN_OPTS='-Xmx256m' mvn test -Dtest=SmallTest").high());
    }

    @Test void chainsUseHighestCostAndComplexSyntaxUsesFallback() throws Exception {
        var policy = smallTestPolicy();
        assertFalse(decide(policy, "mvn test -Dtest=SmallTest && mvn test -Dtest=SmallTest").high());
        assertTrue(decide(policy, "mvn test -Dtest=SmallTest && mvn package").high());
        assertTrue(decide(policy, "mvn test -Dtest=SmallTest | other").high());
        for (String command : new String[]{"mvn test -Dtest=$TEST", "bash -c 'mvn test -Dtest=SmallTest'",
                "env -u FOO mvn test -Dtest=SmallTest", "mvn test -Dtest='SmallTest", "mvn test >out"}) {
            assertTrue(decide(policy, command).high(), command);
        }
    }

    @Test void typedArgumentsAndRulePriority() throws Exception {
        var policy = (ObjectNode) JsonUtils.standardMapper().readTree("""
                {"defaultClass":"high","unknownShellClass":"high","rules":[
                  {"id":"bounded","tool":"local_code_index","class":"low","all":[
                    {"path":"/arguments/action","op":"eq","value":"index"},
                    {"path":"/arguments/background","op":"eq","value":false},
                    {"path":"/arguments/wait_seconds","op":"lte","value":10}
                  ]},
                  {"id":"other","tool":"local_code_index","class":"high","all":[]}
                ]}
                """);
        var args = JsonUtils.standardMapper().createObjectNode().put("action", "index")
                .put("background", false).put("wait_seconds", 5);
        assertFalse(ResourcePolicy.classify(policy, "local_code_index", args).high());
        args.put("background", "false");
        assertTrue(ResourcePolicy.classify(policy, "local_code_index", args).high());
        args.put("background", false).put("wait_seconds", 11);
        assertTrue(ResourcePolicy.classify(policy, "local_code_index", args).high());
    }

    @Test void slashSetPreviewAndInvalidConfigDoNotOverwrite() throws Exception {
        assertTrue(ResourcePolicy.command(root, "set " + smallTestPolicy()).contains("saved"));
        assertTrue(ResourcePolicy.command(root, "preview bash {\"command\":\"mvn test -Dtest=SmallTest\"}")
                .contains("resourceClass=low"));
        assertTrue(ResourcePolicy.command(root, "set {\"rules\":[]}").contains("error"));
        assertEquals(smallTestPolicy(), ResourcePolicy.load(root));
        assertTrue(ResourcePolicy.command(root, "rules").contains("small-test"));
    }

    @Test void editsRulesAndDefaultsAndPreservesArgumentTypes() throws Exception {
        assertTrue(ResourcePolicy.command(root, "default low").contains("saved"));
        assertTrue(ResourcePolicy.command(root, "unknown-shell low").contains("saved"));
        assertTrue(ResourcePolicy.command(root, "add " + smallTestPolicy().path("rules").get(0)).contains("saved"));
        assertTrue(ResourcePolicy.command(root, "add " + smallTestPolicy().path("rules").get(0)).contains("error"));
        assertTrue(ResourcePolicy.command(root, "remove small-test").contains("saved"));
        assertEquals("low", ResourcePolicy.load(root).path("defaultClass").asText());
        assertTrue(ResourcePolicy.command(root, "preview process {\"action\":\"status\"}").contains("non-launch"));
    }

    @Test void flagPairsAndUnsupportedSyntaxDoNotAccidentallyMatch() throws Exception {
        var policy = smallTestPolicy();
        var rule = (ObjectNode) policy.path("rules").get(0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) rule.path("all")).addObject()
                .put("path", "/shell/argv").put("op", "sequence")
                .set("value", JsonUtils.standardMapper().readTree("[\"-pl\",\"my-module\"]"));
        assertFalse(decide(policy, "mvn test -pl my-module -Dtest=SmallTest").high());
        assertTrue(decide(policy, "mvn test -pl other my-module -Dtest=SmallTest").high());
        assertTrue(decide(policy, "mvn test -pl my-module -- -Dtest=SmallTest").high());
        assertNull(ResourcePolicy.shell("mvn test &&"));
        assertEquals("a\\b", ResourcePolicy.shell("mvn \"a\\b\"").get(0).get(1));
    }

    @Test void routineDiagnosticsAreLowButBuildsAndUnknownScriptsStayGuarded() {
        var defaults = ResourcePolicy.defaults();
        for (String command : new String[]{"free -m", "df -h", "nvidia-smi", "uname -a",
                "printf 'status'", "ps -ef && date"}) assertFalse(decide(defaults, command).high(), command);
        for (String command : new String[]{"mvn test", "ninja", "python job.py", "./build.sh",
                "free -m && mvn package", "bash -c 'free -m'"}) assertTrue(decide(defaults, command).high(), command);
    }

    @Test void plainTextRulesApplyToBothLaunchSurfacesAndCanBeRemoved() throws Exception {
        assertTrue(ResourcePolicy.command(root, "rule version low java -version").contains("saved"));
        assertTrue(ResourcePolicy.command(root, "check java -version").contains("resourceClass=low"));
        assertTrue(ResourcePolicy.command(root, "check java -jar app.jar").contains("resourceClass=high"));
        var args = JsonUtils.standardMapper().createObjectNode().put("action", "launch").put("command", "java -version");
        assertFalse(ResourcePolicy.classify(root, "process", args).high());
        assertTrue(ResourcePolicy.command(root, "rule version low java -version").contains("error"));
        assertTrue(ResourcePolicy.command(root, "remove version").contains("saved"));
        assertTrue(ResourcePolicy.classify(root, "process", args).high());
        assertTrue(ResourcePolicy.command(root, "help").contains("no JSON needed"));
        assertTrue(ResourcePolicy.command(root, "sources").contains("from built-in"));
    }

    @Test void sourcePrecedenceAndIndependentFieldInheritance() throws Exception {
        Path user = root.resolve("user.json"), project = root.resolve("project.json");
        java.nio.file.Files.writeString(user, "{\"defaultClass\":\"low\"}");
        java.nio.file.Files.writeString(project, "{\"unknownShellClass\":\"low\"}");
        var merged = ResourcePolicy.loadSources(user, project);
        assertEquals("low", merged.path("defaultClass").asText());
        assertEquals("low", merged.path("unknownShellClass").asText());
        assertEquals(ResourcePolicy.defaults().path("rules"), merged.path("rules"));
        java.nio.file.Files.writeString(project, "{\"defaultClass\":\"high\",\"rules\":[]}");
        merged = ResourcePolicy.loadSources(user, project);
        assertEquals("high", merged.path("defaultClass").asText());
        assertTrue(merged.path("rules").isEmpty());
        java.nio.file.Files.writeString(project, "{\"defautClass\":\"low\"}");
        assertThrows(IllegalArgumentException.class, () -> ResourcePolicy.loadSources(user, project));
    }

    @Test void scalarEditsDoNotFreezeDefaultsAndInheritRemovesOverrides() throws Exception {
        assertTrue(ResourcePolicy.command(root, "default low").contains("saved"));
        var stored = JsonUtils.standardMapper().readTree(root.resolve(".kompile/resource-policy.json").toFile());
        assertEquals(1, stored.size());
        assertFalse(stored.has("rules"));
        assertTrue(ResourcePolicy.command(root, "inherit default").contains("saved"));
        assertFalse(JsonUtils.standardMapper().readTree(root.resolve(".kompile/resource-policy.json").toFile()).has("defaultClass"));
    }

    @Test void malformedPolicyStillAllowsHelpAndExplicitRepair() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve(".kompile"));
        java.nio.file.Files.writeString(root.resolve(".kompile/resource-policy.json"), "not json");
        assertTrue(ResourcePolicy.command(root, "help").contains("no JSON needed"));
        assertTrue(ResourcePolicy.command(root, "show").contains("error"));
        assertTrue(ResourcePolicy.command(root, "set " + smallTestPolicy()).contains("saved"));
    }

    @Test void globalChangesAreScopedAndProjectOverridesWin() throws Exception {
        Path user = root.resolve("user/resource-policy.json");
        assertTrue(ResourcePolicy.command(root, "global default low", user).contains("saved"));
        assertFalse(java.nio.file.Files.exists(root.resolve(".kompile/resource-policy.json")));
        assertTrue(ResourcePolicy.command(root, "check custom-job", user).contains("resourceClass=low"));
        assertTrue(ResourcePolicy.command(root, "default high", user).contains("saved"));
        assertTrue(ResourcePolicy.command(root, "check custom-job", user).contains("resourceClass=high"));
        assertTrue(ResourcePolicy.command(root, "global check custom-job", user).contains("resourceClass=low"));
        assertTrue(ResourcePolicy.command(root, "sources", user).contains("defaultClass: high (from project)"));
        assertTrue(ResourcePolicy.command(root, "inherit default", user).contains("saved"));
        assertTrue(ResourcePolicy.command(root, "check custom-job", user).contains("resourceClass=low"));
    }

    private ResourcePolicy.Decision decide(ObjectNode policy, String command) {
        return ResourcePolicy.classify(policy, "bash", JsonUtils.standardMapper().createObjectNode().put("command", command));
    }
}
