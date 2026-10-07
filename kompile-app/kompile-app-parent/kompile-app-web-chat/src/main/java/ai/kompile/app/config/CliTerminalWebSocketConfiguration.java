package ai.kompile.app.config;

import ai.kompile.app.services.agent.CliTerminalService;
import ai.kompile.app.web.controllers.CliTerminalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
public class CliTerminalWebSocketConfiguration implements WebSocketConfigurer {
    private final CliTerminalService terminals;
    private final ObjectMapper mapper;
    public CliTerminalWebSocketConfiguration(CliTerminalService terminals, ObjectMapper mapper) {
        this.terminals = terminals; this.mapper = mapper;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TerminalHandler(), "/api/agents/chat/terminal/socket/*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                             WebSocketHandler handler, Map<String, Object> attributes) {
                        if (!(request instanceof ServletServerHttpRequest servlet)) return false;
                        try {
                            var http = servlet.getServletRequest();
                            CliTerminalController.requireAccess(http, false);
                            if (http.getHeader("Origin") == null || http.getSession(false) == null) return false;
                            attributes.put("owner", http.getSession(false).getId());
                            String path = http.getRequestURI();
                            attributes.put("terminal", path.substring(path.lastIndexOf('/') + 1));
                            return true;
                        } catch (RuntimeException denied) { return false; }
                    }
                    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                         WebSocketHandler handler, Exception exception) { }
                }); // Spring's default same-origin policy, in addition to the loopback guard.
    }
    private final class TerminalHandler extends TextWebSocketHandler {
        private String owner(WebSocketSession socket) { return (String) socket.getAttributes().get("owner"); }
        private String id(WebSocketSession socket) { return (String) socket.getAttributes().get("terminal"); }
        @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
            socket.setTextMessageSizeLimit(65536);
            try { terminals.attach(owner(socket), id(socket), socket); }
            catch (RuntimeException | java.io.IOException invalid) { socket.close(CloseStatus.POLICY_VIOLATION); }
        }
        @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
            try { terminals.message(owner(socket), id(socket), socket.getId(), mapper.readTree(message.getPayload())); }
            catch (RuntimeException | java.io.IOException invalid) { socket.close(CloseStatus.POLICY_VIOLATION); }
        }
        @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
            terminals.detach(owner(socket), id(socket), socket.getId());
        }
        @Override public void handleTransportError(WebSocketSession socket, Throwable exception) throws Exception {
            terminals.detach(owner(socket), id(socket), socket.getId());
            socket.close(CloseStatus.SERVER_ERROR);
        }
    }
}
