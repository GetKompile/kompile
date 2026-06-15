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

package ai.kompile.process.domain;

import ai.kompile.process.agent.*;
import ai.kompile.process.hitl.*;
import ai.kompile.process.ingest.NormalizationRule;
import ai.kompile.process.ingest.NormalizationType;
import ai.kompile.process.ontology.ChangeRecord;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.workflow.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that all process-engine domain model classes survive Jackson
 * JSON serialization roundtrips. These classes are persisted as JSON in
 * workflow runData, approval requests, and process definitions.
 */
class ProcessDomainModelSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── HITL domain ─────────────────────────────────────────────────────────

    @Nested
    class HitlDomain {

        @Test
        void approvalItem_roundtrip() throws Exception {
            ApprovalItem item = ApprovalItem.builder()
                    .id("item-001")
                    .description("Auto-correct GBP amount")
                    .patternId("pattern-fx-mismatch")
                    .before(Map.of("amount", 280000, "currency", "USD"))
                    .after(Map.of("amount", 280000, "currency", "GBP"))
                    .confidence(0.92)
                    .status(ApprovalItemStatus.PENDING)
                    .rejectionReason(null)
                    .build();

            String json = mapper.writeValueAsString(item);
            ApprovalItem deserialized = mapper.readValue(json, ApprovalItem.class);

            assertEquals(item, deserialized);
            assertEquals("item-001", deserialized.getId());
            assertEquals(0.92, deserialized.getConfidence(), 0.001);
            assertEquals(ApprovalItemStatus.PENDING, deserialized.getStatus());
            assertNull(deserialized.getRejectionReason());
        }

        @Test
        void approvalItem_rejectedState() throws Exception {
            ApprovalItem item = ApprovalItem.builder()
                    .id("item-002")
                    .description("Remove duplicate entry")
                    .confidence(0.45)
                    .status(ApprovalItemStatus.REJECTED)
                    .rejectionReason("Duplicate is intentional — separate PO lines")
                    .build();

            String json = mapper.writeValueAsString(item);
            ApprovalItem deserialized = mapper.readValue(json, ApprovalItem.class);

            assertEquals(ApprovalItemStatus.REJECTED, deserialized.getStatus());
            assertEquals("Duplicate is intentional — separate PO lines", deserialized.getRejectionReason());
        }

        @Test
        void itemDecision_roundtrip() throws Exception {
            ItemDecision decision = ItemDecision.builder()
                    .itemId("item-001")
                    .accepted(true)
                    .reason("Confirmed with counterparty")
                    .build();

            String json = mapper.writeValueAsString(decision);
            ItemDecision deserialized = mapper.readValue(json, ItemDecision.class);

            assertEquals(decision, deserialized);
            assertTrue(deserialized.isAccepted());
        }

        @Test
        void fieldEdit_roundtrip() throws Exception {
            FieldEdit edit = FieldEdit.builder()
                    .fieldPath("lineItems[3].amount")
                    .oldValue(150000)
                    .newValue(155000)
                    .reason("Updated to reflect amended invoice")
                    .build();

            String json = mapper.writeValueAsString(edit);
            FieldEdit deserialized = mapper.readValue(json, FieldEdit.class);

            assertEquals(edit, deserialized);
            assertEquals("lineItems[3].amount", deserialized.getFieldPath());
        }

        @Test
        void assumption_roundtrip() throws Exception {
            Instant expires = Instant.parse("2026-06-01T00:00:00Z");
            Assumption assumption = Assumption.builder()
                    .lineItemId("line-42")
                    .description("Boots GBP 280k assumes PO closes this week")
                    .probability(0.75)
                    .expiresAt(expires)
                    .impact("$280k downside if PO not closed")
                    .build();

            String json = mapper.writeValueAsString(assumption);
            Assumption deserialized = mapper.readValue(json, Assumption.class);

            assertEquals(assumption, deserialized);
            assertEquals(0.75, deserialized.getProbability(), 0.001);
            assertEquals(expires, deserialized.getExpiresAt());
        }

        @Test
        void cellExclusion_entireSheet() throws Exception {
            CellExclusion exclusion = CellExclusion.builder()
                    .workbookId("wb-budget-2026")
                    .sheet("Summary")
                    .range(null)
                    .reason("Summary tab contains stale totals")
                    .assertedBy("alice@acme.com")
                    .assertedAt(Instant.parse("2026-05-20T10:30:00Z"))
                    .build();

            String json = mapper.writeValueAsString(exclusion);
            CellExclusion deserialized = mapper.readValue(json, CellExclusion.class);

            assertEquals(exclusion, deserialized);
            assertNull(deserialized.getRange(), "null range means entire sheet excluded");
            assertEquals("Summary", deserialized.getSheet());
        }

        @Test
        void cellExclusion_specificRange() throws Exception {
            CellExclusion exclusion = CellExclusion.builder()
                    .workbookId("wb-budget-2026")
                    .sheet("Revenue")
                    .range("A60:F60")
                    .reason("GRAND TOTAL row is stale")
                    .assertedBy("bob@acme.com")
                    .assertedAt(Instant.now())
                    .build();

            String json = mapper.writeValueAsString(exclusion);
            CellExclusion deserialized = mapper.readValue(json, CellExclusion.class);

            assertEquals("A60:F60", deserialized.getRange());
        }
    }

    // ── Agent domain ────────────────────────────────────────────────────────

    @Nested
    class AgentDomain {

        @Test
        void agentTool_roundtrip() throws Exception {
            AgentTool tool = AgentTool.builder()
                    .name("read_workbook")
                    .description("Read cells from an Excel workbook")
                    .parameterSchema(Map.of(
                            "type", "object",
                            "properties", Map.of("path", Map.of("type", "string"))))
                    .permission(ToolPermission.READ)
                    .build();

            String json = mapper.writeValueAsString(tool);
            AgentTool deserialized = mapper.readValue(json, AgentTool.class);

            assertEquals(tool, deserialized);
            assertEquals(ToolPermission.READ, deserialized.getPermission());
        }

        @Test
        void agentPermissions_roundtrip() throws Exception {
            AgentPermissions perms = AgentPermissions.builder()
                    .readSources(List.of("emails", "workbooks"))
                    .writeSources(List.of("workbooks"))
                    .neverWrite(List.of("audit_log"))
                    .maxActionsPerRun(50)
                    .dollarThreshold(100000)
                    .build();

            String json = mapper.writeValueAsString(perms);
            AgentPermissions deserialized = mapper.readValue(json, AgentPermissions.class);

            assertEquals(perms, deserialized);
            assertEquals(2, deserialized.getReadSources().size());
            assertEquals(50, deserialized.getMaxActionsPerRun());
        }

        @Test
        void auditTrailSchema_roundtrip() throws Exception {
            AuditTrailSchema schema = AuditTrailSchema.builder()
                    .perActionFields(List.of("timestamp", "actor", "action"))
                    .perEscalationFields(List.of("timestamp", "escalatedTo", "reason"))
                    .includeInputHash(true)
                    .includeOutputHash(true)
                    .includeEvidenceSnapshot(false)
                    .build();

            String json = mapper.writeValueAsString(schema);
            AuditTrailSchema deserialized = mapper.readValue(json, AuditTrailSchema.class);

            assertEquals(schema, deserialized);
            assertTrue(deserialized.isIncludeInputHash());
            assertFalse(deserialized.isIncludeEvidenceSnapshot());
        }

        @Test
        void escalationPolicy_withRoutes_roundtrip() throws Exception {
            EscalationRoute defaultRoute = EscalationRoute.builder()
                    .assignTo("ops-team")
                    .slaSeconds(3600)
                    .fallbackAssignTo("director")
                    .alwaysEscalate(false)
                    .build();

            EscalationRoute failureRoute = EscalationRoute.builder()
                    .assignTo("engineering")
                    .slaSeconds(1800)
                    .alwaysEscalate(true)
                    .build();

            EscalationPolicy policy = EscalationPolicy.builder()
                    .routes(Map.of("high-confidence", defaultRoute))
                    .defaultRoute(defaultRoute)
                    .onAgentFailure(failureRoute)
                    .build();

            String json = mapper.writeValueAsString(policy);
            EscalationPolicy deserialized = mapper.readValue(json, EscalationPolicy.class);

            assertEquals(policy, deserialized);
            assertNotNull(deserialized.getRoutes().get("high-confidence"));
            assertEquals(3600, deserialized.getDefaultRoute().getSlaSeconds());
            assertTrue(deserialized.getOnAgentFailure().isAlwaysEscalate());
        }

        @Test
        void escalationRoute_roundtrip() throws Exception {
            EscalationRoute route = EscalationRoute.builder()
                    .assignTo("compliance-officer")
                    .slaSeconds(7200)
                    .fallbackAssignTo("cfo")
                    .alwaysEscalate(false)
                    .build();

            String json = mapper.writeValueAsString(route);
            EscalationRoute deserialized = mapper.readValue(json, EscalationRoute.class);

            assertEquals(route, deserialized);
        }
    }

    // ── Workflow domain ─────────────────────────────────────────────────────

    @Nested
    class WorkflowDomain {

        @Test
        void stepTrigger_onStepComplete() throws Exception {
            StepTrigger trigger = StepTrigger.builder()
                    .type(TriggerType.ON_STEP_COMPLETE)
                    .sourceStepId("step-1.1")
                    .build();

            String json = mapper.writeValueAsString(trigger);
            StepTrigger deserialized = mapper.readValue(json, StepTrigger.class);

            assertEquals(trigger, deserialized);
            assertEquals(TriggerType.ON_STEP_COMPLETE, deserialized.getType());
            assertEquals("step-1.1", deserialized.getSourceStepId());
        }

        @Test
        void stepTrigger_onSchedule() throws Exception {
            StepTrigger trigger = StepTrigger.builder()
                    .type(TriggerType.ON_SCHEDULE)
                    .cronExpression("0 0 8 * * MON-FRI")
                    .build();

            String json = mapper.writeValueAsString(trigger);
            StepTrigger deserialized = mapper.readValue(json, StepTrigger.class);

            assertEquals(TriggerType.ON_SCHEDULE, deserialized.getType());
            assertEquals("0 0 8 * * MON-FRI", deserialized.getCronExpression());
        }

        @Test
        void stepTrigger_onFileArrival() throws Exception {
            StepTrigger trigger = StepTrigger.builder()
                    .type(TriggerType.ON_FILE_ARRIVAL)
                    .watchPath("/data/inbox/*.xlsx")
                    .build();

            String json = mapper.writeValueAsString(trigger);
            StepTrigger deserialized = mapper.readValue(json, StepTrigger.class);

            assertEquals(TriggerType.ON_FILE_ARRIVAL, deserialized.getType());
        }

        @Test
        void timeoutPolicy_roundtrip() throws Exception {
            TimeoutPolicy policy = TimeoutPolicy.builder()
                    .slaSeconds(300)
                    .hardCapSeconds(600)
                    .onTimeout(TimeoutAction.ESCALATE)
                    .escalateTo("ops-lead")
                    .build();

            String json = mapper.writeValueAsString(policy);
            TimeoutPolicy deserialized = mapper.readValue(json, TimeoutPolicy.class);

            assertEquals(policy, deserialized);
            assertEquals(300, deserialized.getSlaSeconds());
            assertEquals(TimeoutAction.ESCALATE, deserialized.getOnTimeout());
        }

        @Test
        void errorPolicy_retry() throws Exception {
            ErrorPolicy policy = ErrorPolicy.builder()
                    .onError(ErrorAction.RETRY)
                    .maxRetries(3)
                    .retryBackoffMs(1000)
                    .build();

            String json = mapper.writeValueAsString(policy);
            ErrorPolicy deserialized = mapper.readValue(json, ErrorPolicy.class);

            assertEquals(policy, deserialized);
            assertEquals(ErrorAction.RETRY, deserialized.getOnError());
            assertEquals(3, deserialized.getMaxRetries());
        }

        @Test
        void errorPolicy_routeToBranch() throws Exception {
            ErrorPolicy policy = ErrorPolicy.builder()
                    .onError(ErrorAction.ROUTE_TO_BRANCH)
                    .errorBranchStepId("error-handler-step")
                    .build();

            String json = mapper.writeValueAsString(policy);
            ErrorPolicy deserialized = mapper.readValue(json, ErrorPolicy.class);

            assertEquals(ErrorAction.ROUTE_TO_BRANCH, deserialized.getOnError());
            assertEquals("error-handler-step", deserialized.getErrorBranchStepId());
        }

        @Test
        void delegationPolicy_roundtrip() throws Exception {
            DelegationPolicy policy = DelegationPolicy.builder()
                    .timeoutSeconds(86400)
                    .delegateTo("backup-reviewer")
                    .allowOOOAutoDelegate(true)
                    .oooCalendarSource("google-calendar")
                    .build();

            String json = mapper.writeValueAsString(policy);
            DelegationPolicy deserialized = mapper.readValue(json, DelegationPolicy.class);

            assertEquals(policy, deserialized);
            assertTrue(deserialized.isAllowOOOAutoDelegate());
        }

        @Test
        void escalationOverride_roundtrip() throws Exception {
            EscalationOverride override = EscalationOverride.builder()
                    .patternId("high-value-trades")
                    .escalateTo("senior-trader")
                    .reason("All trades over $1M need senior review")
                    .alwaysEscalate(true)
                    .build();

            String json = mapper.writeValueAsString(override);
            EscalationOverride deserialized = mapper.readValue(json, EscalationOverride.class);

            assertEquals(override, deserialized);
            assertTrue(deserialized.isAlwaysEscalate());
        }
    }

    // ── Ontology domain ─────────────────────────────────────────────────────

    @Nested
    class OntologyDomain {

        @Test
        void fieldDefinition_stringWithConstraints() throws Exception {
            FieldDefinition field = FieldDefinition.builder()
                    .name("isin_code")
                    .type(FieldType.STRING)
                    .maxLength(12)
                    .required(true)
                    .immutable(true)
                    .primaryKey(false)
                    .regex("[A-Z]{2}[A-Z0-9]{10}")
                    .description("ISIN security identifier")
                    .build();

            String json = mapper.writeValueAsString(field);
            FieldDefinition deserialized = mapper.readValue(json, FieldDefinition.class);

            assertEquals(field, deserialized);
            assertTrue(deserialized.isRequired());
            assertTrue(deserialized.isImmutable());
            assertEquals(FieldType.STRING, deserialized.getType());
        }

        @Test
        void fieldDefinition_decimalWithMinMax() throws Exception {
            FieldDefinition field = FieldDefinition.builder()
                    .name("interest_rate")
                    .type(FieldType.DECIMAL)
                    .min(0.0)
                    .max(100.0)
                    .required(false)
                    .build();

            String json = mapper.writeValueAsString(field);
            FieldDefinition deserialized = mapper.readValue(json, FieldDefinition.class);

            assertEquals(0.0, deserialized.getMin(), 0.001);
            assertEquals(100.0, deserialized.getMax(), 0.001);
        }

        @Test
        void fieldDefinition_enumWithAllowedValues() throws Exception {
            FieldDefinition field = FieldDefinition.builder()
                    .name("status")
                    .type(FieldType.ENUM)
                    .enumValues(List.of("OPEN", "CLOSED", "PENDING"))
                    .required(true)
                    .defaultValue("OPEN")
                    .build();

            String json = mapper.writeValueAsString(field);
            FieldDefinition deserialized = mapper.readValue(json, FieldDefinition.class);

            assertEquals(3, deserialized.getEnumValues().size());
            assertEquals("OPEN", deserialized.getDefaultValue());
        }

        @Test
        void fieldDefinition_foreignKey() throws Exception {
            FieldDefinition field = FieldDefinition.builder()
                    .name("currency_code")
                    .type(FieldType.STRING)
                    .fkReference("CurrencyRegistry.code")
                    .build();

            String json = mapper.writeValueAsString(field);
            FieldDefinition deserialized = mapper.readValue(json, FieldDefinition.class);

            assertEquals("CurrencyRegistry.code", deserialized.getFkReference());
        }

        @Test
        void changeRecord_roundtrip() throws Exception {
            ChangeRecord record = ChangeRecord.builder()
                    .timestamp(Instant.parse("2026-05-20T14:30:00Z"))
                    .changedBy("alice@acme.com")
                    .changeType("UPDATE")
                    .description("Added interest_rate field to BondTrade entity")
                    .previousValue(null)
                    .newValue("{\"name\":\"interest_rate\",\"type\":\"DECIMAL\"}")
                    .build();

            String json = mapper.writeValueAsString(record);
            ChangeRecord deserialized = mapper.readValue(json, ChangeRecord.class);

            assertEquals(record, deserialized);
            assertEquals("UPDATE", deserialized.getChangeType());
            assertNull(deserialized.getPreviousValue());
        }
    }

    // ── Ingest domain ───────────────────────────────────────────────────────

    @Nested
    class IngestDomain {

        @Test
        void normalizationRule_roundtrip() throws Exception {
            NormalizationRule rule = NormalizationRule.builder()
                    .id("norm-fx-usd")
                    .type(NormalizationType.FX_RATE_OVERRIDE)
                    .description("Override FX rate with treasury-locked rate")
                    .expression("amount * fxRate")
                    .appliesTo("*.amount")
                    .parameters(Map.of("targetCurrency", "USD"))
                    .applied(false)
                    .build();

            String json = mapper.writeValueAsString(rule);
            NormalizationRule deserialized = mapper.readValue(json, NormalizationRule.class);

            assertEquals(rule, deserialized);
            assertEquals(NormalizationType.FX_RATE_OVERRIDE, deserialized.getType());
            assertFalse(deserialized.isApplied());
        }

        @Test
        void normalizationRule_appliedState() throws Exception {
            NormalizationRule rule = NormalizationRule.builder()
                    .id("norm-magnitude")
                    .type(NormalizationType.MAGNITUDE_SCALING)
                    .description("Scale JPY thousands to actuals")
                    .applied(true)
                    .appliedBy("system")
                    .appliedAt(Instant.now())
                    .build();

            String json = mapper.writeValueAsString(rule);
            NormalizationRule deserialized = mapper.readValue(json, NormalizationRule.class);

            assertTrue(deserialized.isApplied());
            assertEquals("system", deserialized.getAppliedBy());
            assertNotNull(deserialized.getAppliedAt());
        }
    }

    // ── Default / null safety ───────────────────────────────────────────────

    @Nested
    class DefaultValues {

        @Test
        void noArgConstructors_produceNonThrowingObjects() {
            assertDoesNotThrow(() -> new ApprovalItem());
            assertDoesNotThrow(() -> new ItemDecision());
            assertDoesNotThrow(() -> new FieldEdit());
            assertDoesNotThrow(() -> new Assumption());
            assertDoesNotThrow(() -> new CellExclusion());
            assertDoesNotThrow(() -> new AgentTool());
            assertDoesNotThrow(() -> new AgentPermissions());
            assertDoesNotThrow(() -> new AuditTrailSchema());
            assertDoesNotThrow(() -> new EscalationPolicy());
            assertDoesNotThrow(() -> new EscalationRoute());
            assertDoesNotThrow(() -> new StepTrigger());
            assertDoesNotThrow(() -> new TimeoutPolicy());
            assertDoesNotThrow(() -> new ErrorPolicy());
            assertDoesNotThrow(() -> new DelegationPolicy());
            assertDoesNotThrow(() -> new EscalationOverride());
            assertDoesNotThrow(() -> new FieldDefinition());
            assertDoesNotThrow(() -> new ChangeRecord());
            assertDoesNotThrow(() -> new NormalizationRule());
        }

        @Test
        void emptyObjects_serializeAsValidJson() throws Exception {
            String json = mapper.writeValueAsString(new ApprovalItem());
            assertNotNull(json);
            assertTrue(json.startsWith("{"));

            // Can deserialize back
            ApprovalItem deserialized = mapper.readValue(json, ApprovalItem.class);
            assertNotNull(deserialized);
        }
    }
}
