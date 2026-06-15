package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import ai.kompile.graphchangetracking.repository.GraphUpdatePipelineConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class GraphUpdatePipelineConfigService {

    private final GraphUpdatePipelineConfigRepository repo;

    public GraphUpdatePipelineConfigService(GraphUpdatePipelineConfigRepository repo) {
        this.repo = repo;
    }

    public GraphUpdatePipelineConfig create(GraphUpdatePipelineConfig config) {
        return repo.save(config);
    }

    @Transactional
    public Optional<GraphUpdatePipelineConfig> update(String pipelineId, GraphUpdatePipelineConfig update) {
        return repo.findByPipelineId(pipelineId).map(existing -> {
            if (update.getPipelineName() != null) existing.setPipelineName(update.getPipelineName());
            if (update.getEnabled() != null) existing.setEnabled(update.getEnabled());
            if (update.getTriggerChannels() != null) existing.setTriggerChannels(update.getTriggerChannels());
            if (update.getTriggerEventTypes() != null) existing.setTriggerEventTypes(update.getTriggerEventTypes());
            if (update.getFilterJson() != null) existing.setFilterJson(update.getFilterJson());
            if (update.getTargetFactSheetId() != null) existing.setTargetFactSheetId(update.getTargetFactSheetId());
            if (update.getProcessingSteps() != null) existing.setProcessingSteps(update.getProcessingSteps());
            if (update.getRequireApproval() != null) existing.setRequireApproval(update.getRequireApproval());
            if (update.getPriority() != null) existing.setPriority(update.getPriority());
            return repo.save(existing);
        });
    }

    @Transactional
    public boolean delete(String pipelineId) {
        Optional<GraphUpdatePipelineConfig> existing = repo.findByPipelineId(pipelineId);
        if (existing.isPresent()) {
            repo.delete(existing.get());
            return true;
        }
        return false;
    }

    public List<GraphUpdatePipelineConfig> listAll() {
        return repo.findAll();
    }

    public Optional<GraphUpdatePipelineConfig> getById(String pipelineId) {
        return repo.findByPipelineId(pipelineId);
    }

    public List<GraphUpdatePipelineConfig> getEnabledForChannel(String channelName) {
        return repo.findEnabledByChannel(channelName);
    }

    @Transactional
    public Optional<GraphUpdatePipelineConfig> setEnabled(String pipelineId, boolean enabled) {
        return repo.findByPipelineId(pipelineId).map(existing -> {
            existing.setEnabled(enabled);
            return repo.save(existing);
        });
    }
}
