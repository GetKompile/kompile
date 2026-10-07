package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.CliTerminalService;
import ai.kompile.cli.common.WebChatContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CliTerminalControllerTest {
    @TempDir Path directory;
    private final Map<String, String> previous = new HashMap<>();
    private CliTerminalService service;
    private CliTerminalController controller;
    @BeforeEach void context() {
        previous.put(WebChatContext.WORKING_DIRECTORY, System.getProperty(WebChatContext.WORKING_DIRECTORY));
        System.setProperty(WebChatContext.WORKING_DIRECTORY, directory.toString());
        service = mock(CliTerminalService.class);
        controller = new CliTerminalController(service);
    }
    @AfterEach void cleanup() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private MockHttpServletRequest local() {
        var request = new MockHttpServletRequest();
        request.setScheme("http"); request.setServerName("localhost"); request.setServerPort(8081);
        request.setRemoteAddr("127.0.0.1"); request.setLocalAddr("127.0.0.1");
        request.addHeader("Origin", "http://localhost:8081");
        request.addHeader("X-Kompile-Terminal", "1");
        return request;
    }
    @Test void loopbackSameOriginCliHandoffIsRequired() {
        assertEquals(true, controller.capabilities(local()).get("available"));
        var request = local(); request.setRemoteAddr("192.168.1.3");
        assertEquals(false, controller.capabilities(request).get("available"));
        assertThrows(ResponseStatusException.class, () -> controller.launch(request, null));
        verifyNoInteractions(service);
    }
    @Test void rejectsRemoteBindDnsRebindingAndCrossOriginIncludingOtherPorts() {
        for (String origin : new String[]{"https://attacker.example", "http://localhost:9999", "null", "http://user@localhost:8081", "http://localhost:8081/path"}) {
            var request = local(); request.removeHeader("Origin"); request.addHeader("Origin", origin);
            assertThrows(ResponseStatusException.class, () -> controller.list(request));
        }
        var rebinding = local(); rebinding.setServerName("attacker.example"); rebinding.removeHeader("Origin");
        assertThrows(ResponseStatusException.class, () -> controller.list(rebinding));
        var remoteBind = local(); remoteBind.setLocalAddr("192.168.1.2");
        assertThrows(ResponseStatusException.class, () -> controller.list(remoteBind));
        verifyNoInteractions(service);
    }
    @Test void mutationRequiresNonSimpleHeaderAndOwnerComesOnlyFromHttpSession() throws Exception {
        var denied = local(); denied.removeHeader("X-Kompile-Terminal");
        assertThrows(ResponseStatusException.class, () -> controller.stop(denied, "id"));
        var first = local(); var second = local();
        controller.launch(first, new CliTerminalService.Launch(null, 80, 24));
        controller.stop(first, "first-terminal");
        controller.list(second);
        assertNotEquals(first.getSession().getId(), second.getSession().getId());
        verify(service).launch(eq(first.getSession().getId()), any());
        verify(service).stop(first.getSession().getId(), "first-terminal");
        verify(service).list(second.getSession().getId());
    }
    @Test void hostedServerDoesNotOfferTerminal() {
        System.clearProperty(WebChatContext.WORKING_DIRECTORY);
        assertEquals(false, controller.capabilities(local()).get("available"));
        assertThrows(ResponseStatusException.class, () -> controller.list(local()));
        verifyNoInteractions(service);
    }
}
