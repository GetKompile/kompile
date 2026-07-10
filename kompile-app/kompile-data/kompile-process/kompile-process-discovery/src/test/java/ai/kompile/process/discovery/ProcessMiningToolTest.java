/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery;

import ai.kompile.process.discovery.mining.MiningProcessDiscoveryService;
import ai.kompile.process.discovery.mining.ProcessMiningConfig;
import ai.kompile.process.discovery.mining.ProcessMiningConfigManager;
import ai.kompile.process.discovery.mining.conformance.ConformanceResult;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.declare.DeclareTemplate;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProcessMiningTool}.
 * Mocks {@link MiningProcessDiscoveryService} and {@link ProcessMiningConfigManager};
 * verifies each @Tool method delegates correctly and handles errors gracefully.
 */
@ExtendWith(MockitoExtension.class)
class ProcessMiningToolTest {

    @Mock
    private MiningProcessDiscoveryService miningService;

    @Mock
    private ProcessMiningConfigManager configManager;

    private ProcessMiningTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new ProcessMiningTool(miningService);
        // Inject configManager via field (it's @Autowired(required=false) so we use reflection)
        var field = ProcessMiningTool.class.getDeclaredField("configManager");
        field.setAccessible(true);
        field.set(tool, configManager);
    }

    // ── process_mine ─────────────────────────────────────────────────────────────

    @Test
    void mine_returnsSuggestionSummary() {
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .id("sugg-1")
                .name("Invoice Approval")
                .confidence(0.87)
                .processKey("proc-key-1")
                .discoveredAt(Instant.parse("2026-07-09T10:00:00Z"))
                .narrative("Invoices are submitted, reviewed, and approved.")
                .phases(List.of())
                .build();

        when(miningService.discoverForFactSheet(eq(42L), eq(0.0), isNull()))
                .thenReturn(suggestion);

        Map<String, Object> result = tool.mine(new ProcessMiningTool.MineInput(42L, null, null));

        assertEquals("success", result.get("status"));
        assertEquals("sugg-1", result.get("suggestionId"));
        assertEquals("Invoice Approval", result.get("name"));
        assertEquals(0.87, result.get("confidence"));
        assertEquals("proc-key-1", result.get("processKey"));
        assertEquals("Invoices are submitted, reviewed, and approved.", result.get("narrative"));
    }

    @Test
    void mine_noSuggestionReturnsEmpty() {
        when(miningService.discoverForFactSheet(anyLong(), anyDouble(), any()))
                .thenReturn(null);

        Map<String, Object> result = tool.mine(new ProcessMiningTool.MineInput(99L, 0.2, null));

        assertEquals("empty", result.get("status"));
        assertNotNull(result.get("message"));
    }

    @Test
    void mine_requiresFactSheetId() {
        Map<String, Object> result = tool.mine(new ProcessMiningTool.MineInput(null, null, null));
        assertEquals("error", result.get("status"));
        assertTrue(result.get("error").toString().contains("factSheetId"));
    }

    @Test
    void mine_handlesServiceException() {
        when(miningService.discoverForFactSheet(anyLong(), anyDouble(), any()))
                .thenThrow(new RuntimeException("Graph unavailable"));

        Map<String, Object> result = tool.mine(new ProcessMiningTool.MineInput(1L, null, null));
        assertEquals("error", result.get("status"));
        assertEquals("Graph unavailable", result.get("error"));
    }

    // ── process_mine_all ─────────────────────────────────────────────────────────

    @Test
    void mineAll_returnsCounts() {
        when(miningService.discoverAll(eq(0.0), isNull()))
                .thenReturn(Map.of(1L, "sugg-a", 2L, "sugg-b"));

        Map<String, Object> result = tool.mineAll(new ProcessMiningTool.MineAllInput(null, null));

        assertEquals("success", result.get("status"));
        assertEquals(2, result.get("count"));
        @SuppressWarnings("unchecked")
        Map<Long, String> bySheet = (Map<Long, String>) result.get("suggestionsByFactSheet");
        assertEquals("sugg-a", bySheet.get(1L));
    }

    @Test
    void mineAll_handlesException() {
        when(miningService.discoverAll(anyDouble(), any()))
                .thenThrow(new RuntimeException("Mining failed"));

        Map<String, Object> result = tool.mineAll(new ProcessMiningTool.MineAllInput(null, null));
        assertEquals("error", result.get("status"));
    }

    // ── process_mining_entailment ────────────────────────────────────────────────

    @Test
    void entailment_returnsSummaryWithFusedOpinion() {
        ProcessEntailmentResult.EntailedPrecedence pair =
                new ProcessEntailmentResult.EntailedPrecedence(
                        "Submit", "Review", 0.92, true, false,
                        5L, 1L, null, List.of(), List.of(), "Precedes(n0,n1)");

        ProcessEntailmentResult entResult = new ProcessEntailmentResult(
                List.of(pair), List.of("1.0: Link(A, B) -> Precedes(A, B)"), true, "run-42");

        when(miningService.entailment(eq(5L), isNull(), eq(0.2), eq(0.66)))
                .thenReturn(entResult);

        Map<String, Object> result = tool.entailment(
                new ProcessMiningTool.EntailmentInput(5L, null, null, null));

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("totalPairs"));
        assertEquals(1, result.get("acceptedPairs"));
        assertEquals(0, result.get("entailedOnlyPairs")); // observed=true
        assertEquals(true, result.get("transitivityApplied"));
        assertNotNull(result.get("fusedOpinion"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs2 = (List<Map<String, Object>>) result.get("acceptedPrecedences");
        assertNotNull(pairs2);
        assertEquals(1, pairs2.size());
        assertEquals("Submit", pairs2.get(0).get("from"));
        assertEquals("Review", pairs2.get(0).get("to"));
        assertEquals(0.92, pairs2.get(0).get("posterior"));
        assertEquals(true, pairs2.get(0).get("observed"));
    }

    @Test
    void entailment_requiresFactSheetId() {
        Map<String, Object> result = tool.entailment(
                new ProcessMiningTool.EntailmentInput(null, null, null, null));
        assertEquals("error", result.get("status"));
    }

    @Test
    void entailment_capsResultsAt20() {
        // Build 25 pairs all above threshold
        List<ProcessEntailmentResult.EntailedPrecedence> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pairs.add(new ProcessEntailmentResult.EntailedPrecedence(
                    "A" + i, "B" + i, 0.8, true, false,
                    3L, 0L, null, List.of(), List.of(), "atom" + i));
        }
        when(miningService.entailment(eq(7L), isNull(), eq(0.2), eq(0.66)))
                .thenReturn(new ProcessEntailmentResult(pairs, List.of(), false, "r1"));

        Map<String, Object> result = tool.entailment(
                new ProcessMiningTool.EntailmentInput(7L, null, null, null));
        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        List<?> shown = (List<?>) result.get("acceptedPrecedences");
        assertEquals(20, shown.size());
        assertEquals(5, result.get("acceptedPrecedencesMore"));
    }

    // ── process_mining_conformance ───────────────────────────────────────────────

    @Test
    void conformance_returnsMetrics() {
        ConformanceResult conf = new ConformanceResult(0.95, 0.88, 0.72, 12, 11, true);
        when(miningService.conformance(eq(3L), eq(0.0), isNull())).thenReturn(conf);

        Map<String, Object> result = tool.conformance(
                new ProcessMiningTool.ConformanceInput(3L, null, null));

        assertEquals("success", result.get("status"));
        assertEquals(0.95, result.get("fitness"));
        assertEquals(0.88, result.get("precision"));
        assertEquals(0.72, result.get("simplicity"));
        double fscore = (double) result.get("fscore");
        assertTrue(fscore > 0.9 && fscore < 0.92); // 2*0.95*0.88/(0.95+0.88) ≈ 0.914
    }

    @Test
    void conformance_requiresFactSheetId() {
        Map<String, Object> result = tool.conformance(
                new ProcessMiningTool.ConformanceInput(null, null, null));
        assertEquals("error", result.get("status"));
    }

    // ── process_mining_declare ───────────────────────────────────────────────────

    @Test
    void declare_returnsConstraints() {
        DeclareConstraint c1 = new DeclareConstraint(DeclareTemplate.RESPONSE, "Submit", "Review", 0.9, 0.95);
        DeclareConstraint c2 = new DeclareConstraint(DeclareTemplate.PRECEDENCE, "Review", "Approve", 0.85, 0.92);

        when(miningService.declareConstraints(eq(10L), eq(0.1), eq(0.9), isNull()))
                .thenReturn(List.of(c1, c2));

        Map<String, Object> result = tool.declare(
                new ProcessMiningTool.DeclareInput(10L, null, null, null));

        assertEquals("success", result.get("status"));
        assertEquals(2, result.get("totalCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> constraints = (List<Map<String, Object>>) result.get("constraints");
        assertEquals(2, constraints.size());
        assertEquals("RESPONSE", constraints.get(0).get("template"));
        assertEquals("Submit", constraints.get(0).get("activityA"));
        assertEquals("Review", constraints.get(0).get("activityB"));
    }

    @Test
    void declare_requiresFactSheetId() {
        Map<String, Object> result = tool.declare(
                new ProcessMiningTool.DeclareInput(null, null, null, null));
        assertEquals("error", result.get("status"));
    }

    // ── process_mining_bpmn ──────────────────────────────────────────────────────

    @Test
    void bpmn_returnsXml() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>";
        when(miningService.bpmnExport(eq(8L), eq(0.0), isNull())).thenReturn(xml);

        Map<String, Object> result = tool.bpmn(new ProcessMiningTool.BpmnInput(8L, null, null));

        assertEquals("success", result.get("status"));
        assertEquals(xml, result.get("bpmnXml"));
        assertEquals(xml.length(), result.get("length"));
    }

    @Test
    void bpmn_nullResultReturnsEmpty() {
        when(miningService.bpmnExport(anyLong(), anyDouble(), any())).thenReturn(null);

        Map<String, Object> result = tool.bpmn(new ProcessMiningTool.BpmnInput(8L, null, null));
        assertEquals("empty", result.get("status"));
    }

    @Test
    void bpmn_requiresFactSheetId() {
        Map<String, Object> result = tool.bpmn(new ProcessMiningTool.BpmnInput(null, null, null));
        assertEquals("error", result.get("status"));
    }

    // ── process_mining_config_get ────────────────────────────────────────────────

    @Test
    void configGet_returnsConfig() {
        ProcessMiningConfig cfg = ProcessMiningConfig.defaults();
        when(configManager.current()).thenReturn(cfg);

        Map<String, Object> result = tool.configGet();

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) result.get("config");
        assertNotNull(config);
        assertTrue(config.containsKey("miningDeclareMinSupport"));
        assertTrue(config.containsKey("miningEntailAssertThreshold"));
    }

    // ── process_mining_config_update ─────────────────────────────────────────────

    @Test
    void configUpdate_callsManagerAndReturnsUpdated() throws Exception {
        ProcessMiningConfig updatedCfg = ProcessMiningConfig.defaults();
        when(configManager.update(any())).thenReturn(Map.of());
        when(configManager.current()).thenReturn(updatedCfg);

        Map<String, Object> input = Map.of("miningDeclareMinSupport", 0.3);
        Map<String, Object> result = tool.configUpdate(new ProcessMiningTool.ConfigUpdateInput(input));

        assertEquals("success", result.get("status"));
        verify(configManager).update(any());
    }

    @Test
    void configUpdate_noManagerReturnsError() throws Exception {
        // Remove configManager
        var field = ProcessMiningTool.class.getDeclaredField("configManager");
        field.setAccessible(true);
        field.set(tool, null);

        Map<String, Object> result = tool.configUpdate(
                new ProcessMiningTool.ConfigUpdateInput(Map.of("miningDeclareMinSupport", 0.3)));
        assertEquals("error", result.get("status"));
    }

    @Test
    void configUpdate_emptyMapReturnsError() {
        Map<String, Object> result = tool.configUpdate(new ProcessMiningTool.ConfigUpdateInput(Map.of()));
        assertEquals("error", result.get("status"));
    }
}
