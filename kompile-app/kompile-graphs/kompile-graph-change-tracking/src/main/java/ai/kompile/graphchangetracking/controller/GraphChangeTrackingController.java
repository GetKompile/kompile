package ai.kompile.graphchangetracking.controller;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.repository.GraphMutationRecordRepository;
import ai.kompile.graphchangetracking.service.TemporalGraphQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph/changes")
public class GraphChangeTrackingController {

    private final TemporalGraphQueryService temporalService;
    private final GraphMutationRecordRepository mutationRepo;

    public GraphChangeTrackingController(TemporalGraphQueryService temporalService,
                                          GraphMutationRecordRepository mutationRepo) {
        this.temporalService = temporalService;
        this.mutationRepo = mutationRepo;
    }

    @GetMapping("/nodes/{nodeId}/history")
    public ResponseEntity<List<GraphMutationRecord>> getNodeHistory(@PathVariable String nodeId) {
        return ResponseEntity.ok(temporalService.getNodeHistory(nodeId));
    }

    @GetMapping("/nodes/{nodeId}/at")
    public ResponseEntity<Map<String, Object>> getNodeAt(@PathVariable String nodeId,
                                                          @RequestParam String time) {
        LocalDateTime asOf = LocalDateTime.parse(time, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return temporalService.reconstructNodeAt(nodeId, asOf)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/edges/{edgeId}/history")
    public ResponseEntity<List<GraphMutationRecord>> getEdgeHistory(@PathVariable String edgeId) {
        return ResponseEntity.ok(temporalService.getEdgeHistory(edgeId));
    }

    @GetMapping("/fact-sheets/{factSheetId}/diff")
    public ResponseEntity<TemporalGraphQueryService.GraphDiff> getDiff(
            @PathVariable Long factSheetId,
            @RequestParam String from,
            @RequestParam String to) {
        LocalDateTime fromTime = LocalDateTime.parse(from, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        LocalDateTime toTime = LocalDateTime.parse(to, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return ResponseEntity.ok(temporalService.diffFactSheet(factSheetId, fromTime, toTime));
    }

    @GetMapping
    public ResponseEntity<Page<GraphMutationRecord>> listChanges(
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);

        if (factSheetId != null && from != null && to != null) {
            LocalDateTime fromTime = LocalDateTime.parse(from, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime toTime = LocalDateTime.parse(to, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ResponseEntity.ok(mutationRepo.findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                    factSheetId, fromTime, toTime, pageable));
        } else if (factSheetId != null) {
            return ResponseEntity.ok(mutationRepo.findByFactSheetIdOrderByOccurredAtDesc(factSheetId, pageable));
        } else if (from != null && to != null) {
            LocalDateTime fromTime = LocalDateTime.parse(from, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime toTime = LocalDateTime.parse(to, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ResponseEntity.ok(mutationRepo.findByOccurredAtBetweenOrderByOccurredAtDesc(fromTime, toTime, pageable));
        } else {
            return ResponseEntity.ok(mutationRepo.findAll(pageable));
        }
    }

    @GetMapping("/changesets/{changesetId}")
    public ResponseEntity<List<GraphMutationRecord>> getChangeset(@PathVariable String changesetId) {
        return ResponseEntity.ok(mutationRepo.findByChangesetId(changesetId));
    }
}
