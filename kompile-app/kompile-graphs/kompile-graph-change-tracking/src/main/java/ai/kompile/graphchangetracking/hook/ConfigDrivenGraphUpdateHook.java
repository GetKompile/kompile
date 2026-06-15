package ai.kompile.graphchangetracking.hook;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import ai.kompile.graphchangetracking.service.MutationContextHolder;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class ConfigDrivenGraphUpdateHook implements GraphUpdateHook {

    private final KnowledgeGraphService graphService;
    private final MutationContextHolder contextHolder;
    private final ObjectMapper objectMapper;

    public ConfigDrivenGraphUpdateHook(KnowledgeGraphService graphService,
                                        MutationContextHolder contextHolder,
                                        ObjectMapper objectMapper) {
        this.graphService = graphService;
        this.contextHolder = contextHolder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return "config-driven-pipeline";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean onChannelMessage(ChannelGraphUpdateContext context) {
        GraphUpdatePipelineConfig config = context.getPipelineConfig();
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return true;
        }

        contextHolder.set(
                context.getChangesetId(),
                "CHANNEL:" + context.getChannelName(),
                context.getMessage().userId()
        );

        try {
            executeSteps(config, context);
        } catch (Exception e) {
            log.error("Pipeline {} failed for channel message {}", config.getPipelineId(),
                    context.getMessage().messageId(), e);
        } finally {
            contextHolder.clear();
        }
        return true;
    }

    private void executeSteps(GraphUpdatePipelineConfig config, ChannelGraphUpdateContext context) {
        String stepsJson = config.getProcessingSteps();
        if (stepsJson == null || stepsJson.isBlank()) {
            return;
        }

        List<Map<String, Object>> steps;
        try {
            steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse processing steps for pipeline {}", config.getPipelineId(), e);
            return;
        }

        for (Map<String, Object> step : steps) {
            String stepType = (String) step.get("step");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) step.getOrDefault("params", Map.of());

            try {
                executeStep(stepType, params, context);
            } catch (Exception e) {
                log.error("Step {} failed in pipeline {}", stepType, config.getPipelineId(), e);
            }
        }
    }

    private void executeStep(String stepType, Map<String, Object> params, ChannelGraphUpdateContext context) {
        switch (stepType) {
            case "UPSERT_NODES" -> executeUpsertNodes(params, context);
            case "CREATE_EDGES" -> executeCreateEdges(params, context);
            default -> log.debug("Unhandled step type: {}", stepType);
        }
    }

    private void executeUpsertNodes(Map<String, Object> params, ChannelGraphUpdateContext context) {
        String nodeTypeStr = (String) params.getOrDefault("nodeType", "ENTITY");
        NodeLevel nodeLevel;
        try {
            nodeLevel = NodeLevel.valueOf(nodeTypeStr);
        } catch (IllegalArgumentException e) {
            nodeLevel = NodeLevel.ENTITY;
        }

        String externalId = "channel:" + context.getChannelName() + ":" + context.getMessage().messageId();
        String title = context.getMessage().userName() + " - " + context.getChannelName();
        String content = context.getMessage().content();
        Map<String, Object> metadata = Map.of(
                "channelName", context.getChannelName(),
                "userId", context.getMessage().userId(),
                "userName", context.getMessage().userName(),
                "messageId", context.getMessage().messageId(),
                "timestamp", context.getMessage().timestamp()
        );

        graphService.createNode(nodeLevel, externalId, title, content, metadata,
                context.getPipelineConfig().getTargetFactSheetId());
    }

    private void executeCreateEdges(Map<String, Object> params, ChannelGraphUpdateContext context) {
        String edgeTypeStr = (String) params.getOrDefault("edgeType", "USER_DEFINED");
        EdgeType edgeType;
        try {
            edgeType = EdgeType.valueOf(edgeTypeStr);
        } catch (IllegalArgumentException e) {
            edgeType = EdgeType.USER_DEFINED;
        }

        Double minWeight = params.containsKey("minWeight") ? ((Number) params.get("minWeight")).doubleValue() : 0.5;
        log.debug("CREATE_EDGES step: edgeType={}, minWeight={} (edges created by extraction steps)", edgeType, minWeight);
    }
}
