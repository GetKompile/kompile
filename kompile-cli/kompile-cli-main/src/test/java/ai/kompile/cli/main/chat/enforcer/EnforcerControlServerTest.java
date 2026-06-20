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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic HTTP tests for the embedded enforcer control server — drives it over real HTTP with a
 * stub {@link EnforcerControlServer.Session} (no agent), verifying that a session can be driven by
 * sending commands and that localhost token auth is enforced.
 */
class EnforcerControlServerTest {

    private final ObjectMapper mapper = JsonUtils.newStandardMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private EnforcerControlServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private EnforcerControlServer.Session stub(AtomicBoolean stopped) {
        return new EnforcerControlServer.Session() {
            @Override
            public EnforcerResult send(String message) {
                return EnforcerResult.accepted("echo: " + message, List.of(), "stub");
            }

            @Override
            public String command(String command, String arg) {
                return "ran " + command + " " + arg;
            }

            @Override
            public Map<String, Object> state() {
                return Map.of("mode", "enforcer-rest", "agent", "stub");
            }

            @Override
            public List<JudgementRecord> judgements() {
                return List.of(JudgementRecord.builder().phase("RESULT").status("ACCEPTED").build());
            }

            @Override
            public void stop() {
                stopped.set(true);
            }
        };
    }

    private HttpResponse<String> req(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + path));
        if (token != null) {
            b.header("X-Kompile-Token", token);
        }
        if ("POST".equals(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            b.GET();
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sendRunsTurnAndReturnsResult() throws Exception {
        server = new EnforcerControlServer(0, "tok", stub(new AtomicBoolean()), mapper);
        server.start();
        HttpResponse<String> r = req("POST", "/send", "tok", "{\"message\":\"hi\"}");
        assertEquals(200, r.statusCode());
        JsonNode j = mapper.readTree(r.body());
        assertEquals("ACCEPTED", j.path("status").asText());
        assertTrue(j.path("accepted").asBoolean());
        assertEquals("echo: hi", j.path("output").asText());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        server = new EnforcerControlServer(0, "tok", stub(new AtomicBoolean()), mapper);
        server.start();
        HttpResponse<String> r = req("POST", "/send", null, "{\"message\":\"hi\"}");
        assertEquals(401, r.statusCode());
    }

    @Test
    void stateJudgementsAndStop() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        server = new EnforcerControlServer(0, null, stub(stopped), mapper); // null token = no auth
        server.start();

        HttpResponse<String> st = req("GET", "/state", null, null);
        assertEquals(200, st.statusCode());
        assertEquals("enforcer-rest", mapper.readTree(st.body()).path("mode").asText());

        HttpResponse<String> jg = req("GET", "/judgements", null, null);
        assertEquals(200, jg.statusCode());
        JsonNode arr = mapper.readTree(jg.body());
        assertTrue(arr.isArray());
        assertEquals("RESULT", arr.get(0).path("phase").asText());

        HttpResponse<String> stop = req("POST", "/stop", null, "");
        assertEquals(200, stop.statusCode());
        assertTrue(stopped.get());
    }

    @Test
    void commandReturnsOutput() throws Exception {
        server = new EnforcerControlServer(0, null, stub(new AtomicBoolean()), mapper);
        server.start();
        HttpResponse<String> r = req("POST", "/command", null, "{\"command\":\"/status\",\"arg\":\"\"}");
        assertEquals(200, r.statusCode());
        assertTrue(mapper.readTree(r.body()).path("output").asText().contains("/status"));
    }
}
