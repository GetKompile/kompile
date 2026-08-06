package ai.kompile.app.web.controllers;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceEndpointsConfigControllerTest {

    @Test
    void getReturnsEveryEffectiveEndpoint(@TempDir Path tmpDir) {
        ServiceEndpointsConfigController controller = controller(tmpDir);

        ResponseEntity<Map<String, Object>> response = controller.getConfig();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("http://localhost:8080", response.getBody().get("adminUrl"));
        assertEquals(ServiceEndpointsConfigManager.DEFAULT_STAGING_URL,
                response.getBody().get(ServiceEndpointsConfigManager.STAGING_URL_KEY));
        assertEquals(ServiceEndpointsConfigManager.DEFAULT_SERVING_URL,
                response.getBody().get(ServiceEndpointsConfigManager.SERVING_URL_KEY));
    }

    @Test
    void postValidatesNormalizesAndPersistsEndpointUrls(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME);
        Files.writeString(file, "{\"foreign\":\"preserved\"}");
        ServiceEndpointsConfigController controller = new ServiceEndpointsConfigController(
                new ServiceEndpointsConfigManager(file));

        ResponseEntity<?> response = controller.updateConfig(Map.of(
                "adminUrl", "http://localhost:18080/",
                "chatUrl", "http://localhost:18081/",
                "crawlUrl", "http://localhost:18082/",
                "stagingUrl", "http://localhost:18090/",
                "servingUrl", "http://127.0.0.1:18091/"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("http://localhost:18081", body.get("chatUrl"));
        assertEquals("http://127.0.0.1:18091", body.get("servingUrl"));
        assertTrue(Files.readString(file).contains("preserved"));
    }

    @Test
    void postRejectsMalformedAndNonLoopbackServingUrls(@TempDir Path tmpDir) {
        ServiceEndpointsConfigController controller = controller(tmpDir);

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.updateConfig(Map.of("chatUrl", "not-a-url")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.updateConfig(Map.of("servingUrl", "http://remote.example:8091"))
                        .getStatusCode());
    }

    @Test
    void defaultControllerReadsTheLaunchingProjectsManagedTopology(@TempDir Path projectDir)
            throws Exception {
        String previousDataDir = System.getProperty("kompile.data.dir");
        try {
            Path configDir = Files.createDirectories(projectDir.resolve("config"));
            Files.writeString(configDir.resolve(ServiceEndpointsConfigManager.FILENAME),
                    "{\"adminUrl\":\"http://127.0.0.1:19580\"," +
                            "\"chatUrl\":\"http://127.0.0.1:19581\"," +
                            "\"crawlUrl\":\"http://127.0.0.1:19582\"}");
            System.setProperty("kompile.data.dir", projectDir.toString());

            ResponseEntity<Map<String, Object>> response =
                    new ServiceEndpointsConfigController().getConfig();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("http://127.0.0.1:19580", response.getBody().get("adminUrl"));
            assertEquals("http://127.0.0.1:19581", response.getBody().get("chatUrl"));
            assertEquals("http://127.0.0.1:19582", response.getBody().get("crawlUrl"));
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("kompile.data.dir");
            } else {
                System.setProperty("kompile.data.dir", previousDataDir);
            }
        }
    }

    @Test
    void stagingDependencyStatusUsesManagedEndpointAndKeepsOfflineStateNonExceptional(@TempDir Path tmpDir)
            throws Exception {
        HttpServer staging = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        staging.createContext("/api/staging/status", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        staging.createContext("/api/service-endpoints", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        staging.start();

        Path file = tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME);
        String endpointUrl = "http://127.0.0.1:" + staging.getAddress().getPort();
        Files.writeString(file, "{\"adminUrl\":\"" + endpointUrl
                + "\",\"stagingUrl\":\"" + endpointUrl + "\"}");
        ServiceEndpointsConfigController controller = new ServiceEndpointsConfigController(
                new ServiceEndpointsConfigManager(file));

        ResponseEntity<Map<String, Object>> online = controller.getDependencyStatus("staging");
        assertEquals(HttpStatus.OK, online.getStatusCode());
        assertNotNull(online.getBody());
        assertEquals(true, online.getBody().get("configured"));
        assertEquals(endpointUrl, online.getBody().get("endpointUrl"));
        assertEquals(true, online.getBody().get("reachable"));
        assertEquals(200, online.getBody().get("statusCode"));

        ResponseEntity<Map<String, Object>> admin = controller.getDependencyStatus("admin");
        assertEquals(HttpStatus.OK, admin.getStatusCode());
        assertNotNull(admin.getBody());
        assertEquals("admin", admin.getBody().get("dependency"));
        assertEquals(true, admin.getBody().get("reachable"));

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.getDependencyStatus("unknown").getStatusCode());

        staging.stop(0);
        ResponseEntity<Map<String, Object>> offline = controller.getDependencyStatus("admin");
        assertEquals(HttpStatus.OK, offline.getStatusCode());
        assertNotNull(offline.getBody());
        assertEquals(false, offline.getBody().get("reachable"));
        assertEquals(0, offline.getBody().get("statusCode"));
        assertTrue(offline.getBody().containsKey("error"));
    }

    private static ServiceEndpointsConfigController controller(Path tmpDir) {
        return new ServiceEndpointsConfigController(new ServiceEndpointsConfigManager(
                tmpDir.resolve(ServiceEndpointsConfigManager.FILENAME)));
    }
}
