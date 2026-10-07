package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ShellMandateHookTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"cd project && sed -n 1p input.txt", "cat input.txt", "printf MATCH | cat",
            "cd project && grep MATCH input.txt", "cd project; rg MATCH", "(cd project && cat)"})
    void nativeBashIsDeniedBeforeExecution(String command) throws Exception {
        var event = mapper.createObjectNode().put("tool_name", "Bash");
        event.putObject("tool_input").put("command", command);
        var error = new ByteArrayOutputStream();
        assertEquals(2, run(event.toString(), error));
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("kompile"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "until test -f ready; do :; done",
            "until ! kill -0 $pid 2>/dev/null; do :; done",
            "until false; do sleep 1; done",
            "bash -c 'until false; do :; done'"
    })
    void nativeUntilLoopsAreDeniedWithMonitorGuidance(String command) throws Exception {
        var event = mapper.createObjectNode().put("tool_name", "Bash");
        event.putObject("tool_input").put("command", command);
        var error = new ByteArrayOutputStream();
        assertEquals(2, run(event.toString(), error));
        String message = error.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("until"), message);
        assertTrue(message.contains("action=monitor"), message);
    }

    @Test
    void compliantBuildAndDedicatedToolsRemainAllowed() throws Exception {
        assertEquals(0, run("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"mvn test\"}}", new ByteArrayOutputStream()));
        assertEquals(0, run("{\"tool_name\":\"mcp__kompile__grep\",\"tool_input\":{\"pattern\":\"MATCH\"}}", new ByteArrayOutputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "null", "{}", "{\"tool_name\":\"Bash\",\"tool_input\":{}}"})
    void unreadableHookEventsFailClosed(String event) {
        assertEquals(2, run(event, new ByteArrayOutputStream()));
    }

    private int run(String event, ByteArrayOutputStream error) {
        return ShellMandateHook.run(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), new PrintStream(error));
    }
}
