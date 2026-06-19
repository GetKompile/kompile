/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.event.observation.controller;

import ai.kompile.core.events.ObservedEventStat;
import ai.kompile.event.observation.config.EventObservationConfig;
import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventChannel;
import ai.kompile.event.observation.domain.EventKeys;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.domain.EventType;
import ai.kompile.event.observation.domain.ObservedEvent;
import ai.kompile.event.observation.service.EventObservationService;
import ai.kompile.event.observation.service.EventPriorService;
import ai.kompile.event.observation.service.GraphEventScanner;
import ai.kompile.event.observation.service.ScanResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * REST API for observed events and empirical priors.
 *
 * <p>Read the priors that feed the Bayesian/MEBN layer, browse top observed events, inspect an
 * event's prior time-series, record a manual observation, trigger a rescan / decay, and edit the
 * JSON config — all under {@code /api/events/observation}.</p>
 */
@RestController
@RequestMapping("/api/events/observation")
public class EventObservationController {

    private final EventPriorService priorService;
    private final EventObservationService observationService;
    private final GraphEventScanner scanner;
    private final EventObservationConfigService configService;

    public EventObservationController(EventPriorService priorService,
                                      EventObservationService observationService,
                                      GraphEventScanner scanner,
                                      EventObservationConfigService configService) {
        this.priorService = priorService;
        this.observationService = observationService;
        this.scanner = scanner;
        this.configService = configService;
    }

    @GetMapping("/priors/node/{nodeId}")
    public ResponseEntity<Map<String, Object>> nodePrior(@PathVariable String nodeId) {
        OptionalDouble prior = priorService.getNodePrior(nodeId);
        Optional<ObservedEventStat> stat = priorService.getNodeStat(nodeId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", nodeId);
        body.put("hasPrior", prior.isPresent());
        body.put("prior", prior.isPresent() ? prior.getAsDouble() : null);
        body.put("stat", stat.orElse(null));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/priors/connection")
    public ResponseEntity<Map<String, Object>> connectionPrior(@RequestParam String source,
                                                               @RequestParam String edgeType,
                                                               @RequestParam String target) {
        OptionalDouble prior = priorService.getConnectionPrior(source, edgeType, target);
        Optional<ObservedEventStat> stat = priorService.getStatByKey(EventKeys.connection(source, edgeType, target));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("edgeType", edgeType);
        body.put("target", target);
        body.put("hasPrior", prior.isPresent());
        body.put("prior", prior.isPresent() ? prior.getAsDouble() : null);
        body.put("stat", stat.orElse(null));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/events")
    public ResponseEntity<List<ObservedEventView>> topEvents(@RequestParam(required = false) Long factSheetId,
                                                             @RequestParam(required = false) String type,
                                                             @RequestParam(defaultValue = "50") int limit) {
        List<ObservedEventView> events = priorService.topEvents(factSheetId, parseType(type), limit)
                .stream().map(ObservedEventView::of).toList();
        return ResponseEntity.ok(events);
    }

    @GetMapping("/events/history")
    public ResponseEntity<List<ObservationHistoryPoint>> history(@RequestParam String key) {
        List<ObservationHistoryPoint> points = priorService.history(key)
                .stream().map(ObservationHistoryPoint::of).toList();
        return ResponseEntity.ok(points);
    }

    @GetMapping("/events/stat")
    public ResponseEntity<ObservedEventStat> stat(@RequestParam String key) {
        return priorService.getStatByKey(key).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/observe")
    public ResponseEntity<ObservedEventView> observe(@RequestBody ObserveRequest req) {
        EventType type = parseType(req.eventType());
        if (type == null) {
            type = EventType.USER_DEFINED;
        }
        long occ = req.occurrences() == null ? 1L : req.occurrences();
        long opp = req.opportunities() == null ? Math.max(1L, occ) : req.opportunities();

        ObservedEvent identity = switch (type) {
            case ENTITY_OCCURRENCE -> ObservedEvent.builder()
                    .eventKey(EventKeys.entity(req.subjectNodeId()))
                    .eventType(type).subjectNodeId(req.subjectNodeId()).factSheetId(req.factSheetId()).build();
            case CONNECTION_OCCURRENCE -> {
                EventChannel ch = new EventChannel(req.sourceNodeId(), req.edgeType(), req.targetNodeId());
                yield ObservedEvent.builder()
                        .eventKey(ch.eventKey()).eventType(type)
                        .sourceNodeId(req.sourceNodeId()).edgeType(req.edgeType()).targetNodeId(req.targetNodeId())
                        .factSheetId(req.factSheetId()).build();
            }
            default -> ObservedEvent.builder()
                    .eventKey(req.eventKey() != null ? req.eventKey() : ("user:" + req.subjectNodeId()))
                    .eventType(type).subjectNodeId(req.subjectNodeId()).factSheetId(req.factSheetId()).build();
        };
        ObservedEvent saved = observationService.observe(identity, occ, opp, EventSource.MANUAL);
        return ResponseEntity.ok(ObservedEventView.of(saved));
    }

    @PostMapping("/rescan")
    public ResponseEntity<ScanResult> rescan(@RequestParam(required = false) Long factSheetId) {
        return ResponseEntity.ok(scanner.scan(factSheetId, EventSource.CRAWL, null));
    }

    @PostMapping("/decay")
    public ResponseEntity<Map<String, Object>> decay() {
        int n = priorService.decayAll();
        return ResponseEntity.ok(Map.of("decayed", n));
    }

    @GetMapping("/config")
    public ResponseEntity<EventObservationConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<EventObservationConfig> updateConfig(@RequestBody EventObservationConfig patch) {
        return ResponseEntity.ok(configService.updateConfig(patch));
    }

    private static EventType parseType(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return EventType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
