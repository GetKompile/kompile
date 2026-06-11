package ai.kompile.app.services.agent;

import ai.kompile.app.config.KompileServerConstants;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for auto-discovering and registering a local kompile model
 * running in the staging module as an OpenAI-compatible API agent.
 */
@Service
public class KompileLocalModelService {

    private static final Logger log = LoggerFactory.getLogger(KompileLocalModelService.class);
    private static final String AGENT_NAME = "kompile-local";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final AgentRegistryService agentRegistryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String stagingUrl = KompileServerConstants.DEFAULT_STAGING_URL;

    private volatile boolean connected = false;
    private volatile String currentModelId = null;

    public KompileLocalModelService(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @PostConstruct
    public void init() {
        // Attempt discovery asynchronously to not block startup
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait for staging module to start
                discoverAndRegister();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Initial kompile-local discovery failed (staging may not be running): {}", e.getMessage());
            }
        });
        thread.setName("kompile-local-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Attempt to discover and register the staging module's local model.
     */
    public Map<String, Object> discoverAndRegister() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String modelsUrl = stagingUrl + "/v1/models";
            HttpURLConnection conn = (HttpURLConnection) new URL(modelsUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                disconnect();
                result.put("success", false);
                result.put("message", "Staging returned HTTP " + responseCode);
                return result;
            }

            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");

            if (!data.isArray() || data.isEmpty()) {
                connected = true;
                currentModelId = null;
                result.put("success", true);
                result.put("message", "Staging reachable but no model loaded");
                result.put("modelLoaded", false);
                return result;
            }

            String modelId = data.get(0).path("id").asText("unknown");
            currentModelId = modelId;
            connected = true;

            // Register as API agent
            AgentProvider agent = AgentProvider.builder()
                    .name(AGENT_NAME)
                    .displayName("Kompile Local (" + modelId + ")")
                    .agentType(AgentType.API)
                    .endpointUrl(stagingUrl + "/v1")
                    .modelName(modelId)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .available(true)
                    .isDefault(false)
                    .description("Local kompile model: " + modelId)
                    .build();

            agentRegistryService.registerAgent(agent);

            result.put("success", true);
            result.put("message", "Registered kompile-local agent with model: " + modelId);
            result.put("modelLoaded", true);
            result.put("modelId", modelId);

            log.info("Registered kompile-local agent: model={}, endpoint={}", modelId, stagingUrl);
            return result;

        } catch (Exception e) {
            disconnect();
            result.put("success", false);
            result.put("message", "Discovery failed: " + e.getMessage());
            log.debug("kompile-local discovery failed: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Connect to a specific staging URL.
     */
    public Map<String, Object> connectTo(String url) {
        this.stagingUrl = url;
        return discoverAndRegister();
    }

    /**
     * Disconnect and unregister the local model agent.
     */
    public void disconnect() {
        connected = false;
        currentModelId = null;
        agentRegistryService.unregisterAgent(AGENT_NAME);
        log.info("Disconnected kompile-local agent");
    }

    /**
     * Get current status of the local model connection.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", connected);
        status.put("stagingUrl", stagingUrl);
        status.put("modelId", currentModelId);
        status.put("modelLoaded", currentModelId != null);
        status.put("agentRegistered", agentRegistryService.getAgent(AGENT_NAME).isPresent());

        if (connected && currentModelId != null) {
            status.put("message", "Connected to " + currentModelId);
        } else if (connected) {
            status.put("message", "Connected but no model loaded");
        } else {
            status.put("message", "Not connected");
        }

        return status;
    }

    public String getStagingUrl() {
        return stagingUrl;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getCurrentModelId() {
        return currentModelId;
    }
}
