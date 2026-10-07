package ai.kompile.app.services.agent;

import ai.kompile.app.config.CliTerminalWebSocketConfiguration;
import ai.kompile.app.web.controllers.CliTerminalController;
import ai.kompile.cli.common.WebChatContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies real HTTP-session ownership and Spring WebSocket transport against a real PTY. */
class CliTerminalTransportTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
            org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration.class})
    @Import({CliTerminalController.class, CliTerminalWebSocketConfiguration.class})
    static class TestServer {
        @Bean CliTerminalService terminals(ObjectMapper mapper) {
            return new CliTerminalService(mapper, () -> java.util.List.of("kompile"), (command, dir, env, cols, rows) ->
                    new PtyProcessBuilder(new String[]{"/bin/sh", "-c",
                            "printf 'READY café\\n'; while IFS= read -r line; do case \"$line\" in size) stty size;; *) printf 'GOT:%s\\n' \"$line\";; esac; done"})
                            .setDirectory(dir.toString()).setEnvironment(env).setInitialColumns(cols).setInitialRows(rows).start());
        }
    }
    @Test void browserTransportStreamsResizesReconnectsAndRejectsOtherOwners() throws Exception {
        String previous = System.getProperty(WebChatContext.WORKING_DIRECTORY);
        System.setProperty(WebChatContext.WORKING_DIRECTORY, directory.toRealPath().toString());
        try (var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TestServer.class)
                .properties(Map.of("server.port", "0", "server.address", "127.0.0.1", "spring.main.banner-mode", "off"))
                .run()) {
            int port = context.getWebServer().getPort();
            String origin = "http://127.0.0.1:" + port;
            String endpoint = origin + "/api/agents/chat/terminal/sessions";
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var created = http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("Origin", origin).header("X-Kompile-Terminal", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"cols\":80,\"rows\":24}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
            String cookie = created.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            String id = mapper.readTree(created.body()).path("id").asText();
            URI socketUri = URI.create("ws://127.0.0.1:" + port + "/api/agents/chat/terminal/socket/" + id);
            Events first = new Events(mapper);
            WebSocket socket = http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", origin)
                    .buildAsync(socketUri, first).get(5, TimeUnit.SECONDS);
            first.await("READY café");
            var transcript = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + id + "/transcript"))
                    .header("Cookie", cookie).header("Origin", origin).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, transcript.statusCode(), transcript.body());
            assertEquals(mapper.readTree(created.body()).path("sessionId"), mapper.readTree(transcript.body()).path("sessionId"));
            var forbiddenTranscript = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + id + "/transcript"))
                    .header("Origin", origin).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, forbiddenTranscript.statusCode(), forbiddenTranscript.body());
            socket.sendText("{\"type\":\"resize\",\"cols\":113,\"rows\":37}", true).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"input\",\"data\":\"size\\nhello\\n\"}", true).get(5, TimeUnit.SECONDS);
            first.await("37 113"); first.await("GOT:hello");
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "detach").get(5, TimeUnit.SECONDS);
            Events replay = new Events(mapper);
            WebSocket reconnected = http.newWebSocketBuilder().header("Cookie", cookie).header("Origin", origin)
                    .buildAsync(socketUri, replay).get(5, TimeUnit.SECONDS);
            replay.await("GOT:hello");
            assertThrows(ExecutionException.class, () -> http.newWebSocketBuilder().header("Cookie", cookie)
                    .header("Origin", "https://attacker.invalid").buildAsync(socketUri, new Events(mapper)).get(5, TimeUnit.SECONDS));
            var other = http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("[]", other.body());
            String otherCookie = other.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            Events foreign = new Events(mapper);
            http.newWebSocketBuilder().header("Cookie", otherCookie).header("Origin", origin)
                    .buildAsync(socketUri, foreign).get(5, TimeUnit.SECONDS);
            assertEquals(1008, foreign.closed.get(5, TimeUnit.SECONDS));
            assertEquals("", foreign.output.toString());
            var stopped = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + id + "/stop"))
                    .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).header("Origin", origin).header("X-Kompile-Terminal", "1")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stopped.statusCode(), stopped.body());
            reconnected.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        } finally {
            if (previous == null) System.clearProperty(WebChatContext.WORKING_DIRECTORY);
            else System.setProperty(WebChatContext.WORKING_DIRECTORY, previous);
        }
    }
    private static final class Events implements WebSocket.Listener {
        final ObjectMapper mapper;
        final StringBuffer output = new StringBuffer();
        final StringBuilder frame = new StringBuilder();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        Events(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                try {
                    var event = mapper.readTree(frame.toString());
                    if (event.path("type").asText().equals("output")) output.append(event.path("data").asText());
                } catch (Exception invalid) { closed.completeExceptionally(invalid); }
                frame.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        void await(String expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.toString().contains(expected) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(output.toString().contains(expected), output.toString());
        }
    }
}
