package ai.kompile.app.services.agent;

import ai.kompile.app.config.KompileServerConstants;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for auto-discovering and registering a local kompile model
 * running in the staging module as an OpenAI-compatible API agent.
 * <p>
 * Discovery is continuous, not startup-only: a background poll keeps the
 * "kompile-local" agent registration in sync with whatever model the staging
 * server currently has loaded, so models loaded/promoted after app startup
 * become selectable in chat without a manual discover call.
 */
@Service
public class KompileLocalModelService {

    private static final Logger log = LoggerFactory.getLogger(KompileLocalModelService.class);
    public static final String AGENT_NAME = "kompile-local";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long INITIAL_DELAY_SECONDS = 2; // let the staging module start first

    private final AgentRegistryService agentRegistryService;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kompile-local-discovery");
        t.setDaemon(true);
        return t;
    });

    /** Re-discovery interval; {@code <= 0} disables polling (single startup attempt only). */
    @Value("${kompile.chat.local-model.poll-seconds:30}")
    private long pollSeconds;

    private volatile String stagingUrl = KompileServerConstants.DEFAULT_STAGING_URL;

    private volatile boolean connected = false;
    private volatile String currentModelId = null;

    public KompileLocalModelService(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @PostConstruct
    public void init() {
        if (pollSeconds > 0) {
            scheduler.scheduleWithFixedDelay(this::pollSafely, INITIAL_DELAY_SECONDS, pollSeconds, TimeUnit.SECONDS);
        } else {
            scheduler.schedule(this::pollSafely, INITIAL_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.debug("kompile-local poll failed: {}", e.getMessage());
        }
    }

    /**
     * Change-driven refresh: registers the agent when a model appears in staging,
     * re-registers on model swap, unregisters when the model is unloaded or staging
     * goes away. Steady state is silent so a down staging server doesn't spam the log.
     */
    private void pollOnce() {
        String liveModelId = null;
        boolean reachable = false;
        try {
            liveModelId = fetchLoadedModelId();
            reachable = true;
        } catch (Exception e) {
            log.debug("kompile-local staging not reachable at {}: {}", stagingUrl, e.getMessage());
        }

        connected = reachable;
        boolean registered = agentRegistryService.getAgent(AGENT_NAME).isPresent();

        if (liveModelId == null) {
            currentModelId = null;
            if (registered) {
                agentRegistryService.unregisterAgent(AGENT_NAME);
                log.info("Unregistered kompile-local agent ({})",
                        reachable ? "staging has no model loaded" : "staging unreachable");
            }
            return;
        }

        if (!registered || !liveModelId.equals(currentModelId)) {
            currentModelId = liveModelId;
            registerCurrentModel(liveModelId);
        }
    }

    /**
     * Attempt to discover and register the staging module's local model.
     */
    public Map<String, Object> discoverAndRegister() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String modelId = fetchLoadedModelId();
            connected = true;

            if (modelId == null) {
                currentModelId = null;
                if (agentRegistryService.getAgent(AGENT_NAME).isPresent()) {
                    agentRegistryService.unregisterAgent(AGENT_NAME);
                    log.info("Unregistered kompile-local agent (staging has no model loaded)");
                }
                result.put("success", true);
                result.put("message", "Staging reachable but no model loaded");
                result.put("modelLoaded", false);
                return result;
            }

            currentModelId = modelId;
            registerCurrentModel(modelId);

            result.put("success", true);
            result.put("message", "Registered kompile-local agent with model: " + modelId);
            result.put("modelLoaded", true);
            result.put("modelId", modelId);
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
     * Queries staging's OpenAI-compatible model list.
     *
     * @return the loaded model id, or {@code null} when staging is up but has no model loaded
     * @throws IOException when staging is unreachable or returns a non-200
     */
    private String fetchLoadedModelId() throws IOException {
        HttpURLConnection conn = null;
        try {
            String modelsUrl = stagingUrl + "/v1/models";
            conn = (HttpURLConnection) new URL(modelsUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("Staging returned HTTP " + responseCode);
            }

            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return null;
            }
            return data.get(0).path("id").asText("unknown");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void registerCurrentModel(String modelId) {
        // Size the generation cap from the staged model's real window: asking a 4K
        // local GGUF for 4096 output tokens leaves no room for the prompt at all.
        int contextWindow = fetchMaxContextLength();
        int maxTokens = contextWindow > 0
                ? Math.max(256, Math.min(4096, contextWindow / 4))
                : 4096;

        AgentProvider agent = AgentProvider.builder()
                .name(AGENT_NAME)
                .displayName("Kompile Local (" + modelId + ")")
                .agentType(AgentType.API)
                .endpointUrl(stagingUrl + "/v1")
                .modelName(modelId)
                .temperature(0.7)
                .maxTokens(maxTokens)
                .available(true)
                .isDefault(false)
                .description("Local kompile model: " + modelId)
                .build();

        agentRegistryService.registerAgent(agent);
        log.info("Registered kompile-local agent: model={}, endpoint={}, maxTokens={} (contextWindow={})",
                modelId, stagingUrl, maxTokens, contextWindow > 0 ? contextWindow : "unknown");
    }

    /**
     * Context window of the loaded model from staging's {@code /api/llm/status},
     * or 0 when unavailable.
     */
    private int fetchMaxContextLength() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(stagingUrl + "/api/llm/status").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                return 0;
            }
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode status = objectMapper.readTree(body);
            return status.path("loaded").asBoolean(false)
                    ? Math.max(0, status.path("maxContextLength").asInt(0))
                    : 0;
        } catch (Exception e) {
            log.debug("Could not read staging LLM context length: {}", e.getMessage());
            return 0;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
