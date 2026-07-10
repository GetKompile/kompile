package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class GraphUpdatePipelineConfigService {

    private final GraphUpdatePipelineConfigStore store;

    public GraphUpdatePipelineConfigService(GraphUpdatePipelineConfigStore store) {
        this.store = store;
    }

    public GraphUpdatePipelineConfig create(GraphUpdatePipelineConfig config) {
        return store.save(config);
    }

    public Optional<GraphUpdatePipelineConfig> update(String pipelineId, GraphUpdatePipelineConfig update) {
        return store.findByPipelineId(pipelineId).map(existing -> {
            if (update.getPipelineName() != null) existing.setPipelineName(update.getPipelineName());
            if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
            if (update.getTriggerChannels() != null) existing.setTriggerChannels(update.getTriggerChannels());
            if (update.getTriggerEventTypes() != null) existing.setTriggerEventTypes(update.getTriggerEventTypes());
            if (update.getFilterJson() != null) existing.setFilterJson(update.getFilterJson());
            if (update.getTargetFactSheetId() != null) existing.setTargetFactSheetId(update.getTargetFactSheetId());
            if (update.getProcessingSteps() != null) existing.setProcessingSteps(update.getProcessingSteps());
            if (update.getRequireApproval() != null) existing.setRequireApproval(update.getRequireApproval());
            if (update.getPriority() != null) existing.setPriority(update.getPriority());
            return store.save(existing);
        });
    }

    public boolean delete(String pipelineId) {
        Optional<GraphUpdatePipelineConfig> existing = store.findByPipelineId(pipelineId);
        if (existing.isPresent()) {
            store.delete(existing.get());
            return true;
        }
        return false;
    }

    public List<GraphUpdatePipelineConfig> listAll() {
        return store.findAll();
    }

    public Optional<GraphUpdatePipelineConfig> getById(String pipelineId) {
        return store.findByPipelineId(pipelineId);
    }

    public List<GraphUpdatePipelineConfig> getEnabledForChannel(String channelName) {
        return store.findEnabledByChannel(channelName);
    }

    public Optional<GraphUpdatePipelineConfig> setEnabled(String pipelineId, boolean enabled) {
        return store.findByPipelineId(pipelineId).map(existing -> {
            existing.setEnabled(enabled);
            return store.save(existing);
        });
    }
}
