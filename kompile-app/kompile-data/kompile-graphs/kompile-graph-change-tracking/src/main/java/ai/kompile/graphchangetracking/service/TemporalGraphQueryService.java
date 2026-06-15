package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.repository.GraphMutationRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class TemporalGraphQueryService {

    private final GraphMutationRecordRepository mutationRepo;
    private final ObjectMapper objectMapper;

    public TemporalGraphQueryService(GraphMutationRecordRepository mutationRepo,
                                      ObjectMapper objectMapper) {
        this.mutationRepo = mutationRepo;
        this.objectMapper = objectMapper;
    }

    public List<GraphMutationRecord> getNodeHistory(String nodeId) {
        return mutationRepo.findByEntityKindAndEntityIdOrderByOccurredAtDesc("NODE", nodeId);
    }

    public List<GraphMutationRecord> getEdgeHistory(String edgeId) {
        return mutationRepo.findByEntityKindAndEntityIdOrderByOccurredAtDesc("EDGE", edgeId);
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> reconstructNodeAt(String nodeId, LocalDateTime asOf) {
        List<GraphMutationRecord> records = mutationRepo.findMostRecentBefore(
                "NODE", nodeId, asOf, PageRequest.of(0, 1));
        if (records.isEmpty()) {
            return Optional.empty();
        }
        GraphMutationRecord latest = records.get(0);
        String snapshot = latest.getSnapshotAfter();
        if (snapshot == null && "NODE_DELETED".equals(latest.getMutationType())) {
            snapshot = latest.getSnapshotBefore();
        }
        if (snapshot == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(snapshot, Map.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize snapshot for node {} at {}", nodeId, asOf, e);
            return Optional.empty();
        }
    }

    public GraphDiff diffFactSheet(Long factSheetId, LocalDateTime from, LocalDateTime to) {
        Page<GraphMutationRecord> mutations = mutationRepo
                .findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                        factSheetId, from, to, Pageable.unpaged());
        List<GraphMutationRecord> records = mutations.getContent();

        int nodesCreated = 0, nodesUpdated = 0, nodesDeleted = 0;
        int edgesCreated = 0, edgesUpdated = 0, edgesDeleted = 0;
        for (GraphMutationRecord r : records) {
            switch (r.getMutationType()) {
                case "NODE_CREATED" -> nodesCreated++;
                case "NODE_UPDATED" -> nodesUpdated++;
                case "NODE_DELETED" -> nodesDeleted++;
                case "EDGE_CREATED" -> edgesCreated++;
                case "EDGE_UPDATED" -> edgesUpdated++;
                case "EDGE_DELETED" -> edgesDeleted++;
            }
        }

        return new GraphDiff(factSheetId, from, to,
                nodesCreated, nodesUpdated, nodesDeleted,
                edgesCreated, edgesUpdated, edgesDeleted,
                records);
    }

    public record GraphDiff(
            Long factSheetId,
            LocalDateTime from,
            LocalDateTime to,
            int nodesCreated,
            int nodesUpdated,
            int nodesDeleted,
            int edgesCreated,
            int edgesUpdated,
            int edgesDeleted,
            List<GraphMutationRecord> mutations
    ) {}
}
