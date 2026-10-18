package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.*;
import ai.kompile.app.web.dto.AgentChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductChatControllerTest {
    @TempDir Path directory;

    @Test void assetsAreFlatContainedSymlinkFreeAndSizeBounded() throws Exception {
        Path assets = Files.createDirectory(directory.resolve("assets"));
        Files.writeString(assets.resolve("index.html"), "<main>play</main>");
        assertEquals("<main>play</main>", new String(ProductChatController.assetBytes(directory, assets, "index.html"), java.nio.charset.StandardCharsets.UTF_8));
        for (String bad : new String[]{"../secret", "sub/main.js", "%2e%2e", "file.exe", "..", ".env"})
            assertThrows(Exception.class, () -> ProductChatController.assetBytes(directory, assets, bad), bad);
        Path outside = Files.createDirectory(directory.resolve("outside")); Files.writeString(outside.resolve("private.js"), "secret");
        Files.createSymbolicLink(assets.resolve("linked.js"), outside.resolve("private.js"));
        assertThrows(IOException.class, () -> ProductChatController.assetBytes(directory, assets, "linked.js"));
        Path linked = directory.resolve("linked"); Files.createSymbolicLink(linked, outside);
        assertThrows(IOException.class, () -> ProductChatController.assetBytes(directory, linked, "private.js"));
        Files.write(assets.resolve("large.js"), new byte[8_388_609]);
        assertThrows(IOException.class, () -> ProductChatController.assetBytes(directory, assets, "large.js"));
    }

    @Test void productMetadataAndReadCapabilitiesNeverProbeAnLlmOrLegacyClient() {
        ProductChatSessionService products = mock(ProductChatSessionService.class);
        ProductChatController controller = new ProductChatController(products);
        Map<String, Object> metadata = Map.of("enabled", true, "chat", Map.of("id", "server-owned", "name", "Elthoria play"));
        when(products.metadata()).thenReturn(metadata);
        var connection = new ObjectMapper().createObjectNode().put("runtimeId", "real-child");
        when(products.readCapability("connection")).thenReturn(connection);
        assertSame(metadata, controller.product()); assertSame(connection, controller.connection());
        verify(products).readCapability("connection");
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, controller::domainUnavailable).getStatusCode());
    }

    @Test void contentApprovalRoutesUseOnlyTheOwnedProductSession() {
        ProductChatSessionService products = mock(ProductChatSessionService.class);
        ProductChatController controller = new ProductChatController(products);
        ObjectMapper mapper = new ObjectMapper();
        var pending = mapper.createArrayNode(); pending.addObject().put("approvalId", "pending-1");
        var body = mapper.createObjectNode().put("allow", true);
        var result = mapper.createObjectNode().put("approvalId", "pending-1").put("ok", true);
        when(products.pendingApprovals()).thenReturn(pending);
        when(products.resolveApproval("pending-1", body)).thenReturn(result);
        assertSame(pending, controller.contentApprovals());
        assertSame(result, controller.contentApproval("pending-1", body));
        verify(products).pendingApprovals(); verify(products).resolveApproval("pending-1", body);
        verifyNoMoreInteractions(products);
    }

    @Test void productTransportDelegatesOnlyToProductServiceAndFailureNeverFallsBack() {
        AgentChatService legacy = mock(AgentChatService.class); ChatHarnessClient harness = mock(ChatHarnessClient.class);
        AgentChatController controller = new AgentChatController(legacy, mock(AgentRegistryService.class), null, harness);
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(true);
        controller.setProductChatSessionService(products);
        AgentChatRequest request = new AgentChatRequest(); SseEmitter expected = new SseEmitter();
        when(products.stream(request)).thenReturn(expected);
        assertSame(expected, controller.streamChat(request, new MockHttpServletRequest()));
        when(products.stream(request)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ResponseStatusException.class, () -> controller.streamChat(request, new MockHttpServletRequest()));
        Map<String, Object> active = Map.of("runId", "harness-product-generation-turn");
        when(products.activeRun("fixed", null)).thenReturn(active);
        assertSame(active, controller.activeRun("fixed", null));
        when(products.reconnect("run", 2)).thenReturn(expected); assertSame(expected, controller.reconnectChat("run", 2));
        when(products.cancel("run")).thenReturn(Map.of("cancelled", true));
        assertEquals(true, controller.cancelChat("run", new MockHttpServletRequest()).getBody().get("cancelled"));
        verifyNoInteractions(legacy, harness);
    }

    @Test void ordinaryControllerStillUsesExistingHarnessWhenProductDisabled() {
        AgentChatService legacy = mock(AgentChatService.class); ChatHarnessClient harness = mock(ChatHarnessClient.class);
        AgentChatController controller = new AgentChatController(legacy, mock(AgentRegistryService.class), null, harness);
        ProductChatSessionService products = mock(ProductChatSessionService.class); when(products.enabled()).thenReturn(false);
        controller.setProductChatSessionService(products);
        AgentChatRequest request = new AgentChatRequest(); request.setMessage("ordinary");
        SseEmitter emitter = controller.streamChat(request, new MockHttpServletRequest());
        verify(harness).executeChat(same(request), same(emitter)); verifyNoInteractions(legacy);
    }
}
