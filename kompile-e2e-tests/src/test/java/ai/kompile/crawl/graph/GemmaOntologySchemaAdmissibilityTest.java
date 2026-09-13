/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import org.eclipse.deeplearning4j.llm.generation.ToolCallParser;
import org.eclipse.deeplearning4j.llm.generation.constraint.ConstraintConfig;
import org.eclipse.deeplearning4j.llm.generation.constraint.ConstraintMasker;
import org.eclipse.deeplearning4j.llm.generation.constraint.GemmaToolCallCodec;
import org.eclipse.deeplearning4j.llm.generation.constraint.TextConstraint;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-module, offline audit of the actual crawl schema; no model/native initialization. */
class GemmaOntologySchemaAdmissibilityTest {
    private static final String Q = ChatTemplate.GEMMA_STRING;
    private static final String OPEN = ChatTemplate.GEMMA_TOOL_CALL_START;
    private static final String CLOSE = ChatTemplate.GEMMA_TOOL_CALL_END;

    private static StructuredChatLanguageModel.Request request() throws Exception {
        GraphSchema baseline = SchemaHierarchyVocabulary.baselineSchema();
        var pass = CorpusSchemaPromptBuilder.TypePass.NODE_TYPES;
        // The proc-078 passage, not a production prompt modification or injected answer.
        Map<String, String> passages = Map.of("founding-1#schema-1",
                "Jordan Lee is a person. Helios Dynamics is a company. "
                        + "Jordan Lee founded Helios Dynamics.");
        String prompt = CorpusSchemaPromptBuilder.build(passages, baseline, pass);
        Method builder = CorpusSchemaUnifier.class.getDeclaredMethod("structuredRequest",
                String.class, CorpusSchemaPromptBuilder.TypePass.class, boolean.class, GraphSchema.class, Set.class);
        builder.setAccessible(true);
        return (StructuredChatLanguageModel.Request) builder.invoke(null, prompt, pass, false, baseline,
                CorpusSchemaPromptBuilder.nodeDiscoveryWindows(passages).keySet());
    }

    private static ChatTemplate.Tool tool() throws Exception {
        var tool = request().tools().get(0);
        return ChatTemplate.Tool.function(tool.name(), tool.description(), tool.parameters());
    }

    private static TextConstraint constraint(ChatTemplate.Tool tool) {
        return ConstraintConfig.gemmaToolCall(Map.of(tool.getName(), List.of("nodeTypes")),
                Map.of(tool.getName(), tool.getParameters())).buildConstraint();
    }

    private static Map<?, ?> child(Map<?, ?> map, String key) {
        return (Map<?, ?>) map.get(key);
    }

    // Structural admissibility only: a valid source span does not establish semantic support for every label.
    private static Map<String, Object> node(String label, String parent) {
        return Map.of("label", label, "parentType", parent,
                "evidence", List.of(Map.of("sourceId", "s1", "quote", "Helios Dynamics is a company.")));
    }

    private static String call(String label, String parent, boolean parentFirst) {
        String labelField = "label:" + Q + label + Q;
        String parentField = "parentType:" + Q + parent + Q;
        return OPEN + "call:submit_node_types{nodeTypes:[{"
                + (parentFirst ? parentField + "," + labelField : labelField + "," + parentField)
                + ",evidence:[{sourceId:" + Q + "s1" + Q + ",quote:" + Q
                + "Helios Dynamics is a company." + Q + "}]}]}" + CLOSE;
    }

    @Test
    void actualRequestKeepsOpenLabelsSeparateFromParentEnumAndRenderedContext() throws Exception {
        var request = request();
        var tool = tool();
        Map<?, ?> array = child(child(tool.getParameters(), "properties"), "nodeTypes");
        Map<?, ?> fields = child(child(array, "items"), "properties");
        Map<?, ?> label = child(fields, "label");
        Map<?, ?> parent = child(fields, "parentType");
        assertEquals("^[A-Z][A-Z0-9_]*$", label.get("pattern"));
        assertEquals(48, label.get("maxLength"));
        assertFalse(label.containsKey("enum"));
        assertFalse(label.containsKey("const"));
        assertEquals(SchemaHierarchyVocabulary.BASE_ENTITY_TYPES, parent.get("enum"));
        assertEquals(32, array.get("maxItems"));
        assertEquals(true, array.get("uniqueItems"));
        assertEquals(StructuredChatLanguageModel.ToolChoice.REQUIRED, request.toolChoice());

        ChatTemplate template = new ChatTemplate(OPEN + CLOSE + "<|tool>declaration:<tool|>", "", "");
        String rendered = template.apply(ChatTemplate.Request.builder()
                .tools(List.of(tool)).toolChoice(ChatTemplate.ToolChoice.REQUIRED)
                .messages(request.messages().stream().map(m ->
                        new ChatTemplate.Message(m.role(), m.content())).toList()).build());
        String labelDeclaration = rendered.substring(rendered.indexOf("label:{"),
                rendered.indexOf(",parentType:{"));
        assertTrue(labelDeclaration.contains("type:" + Q + "STRING" + Q), labelDeclaration);
        assertTrue(labelDeclaration.contains("A reusable corpus-derived category, never an instance."), labelDeclaration);
        assertFalse(labelDeclaration.contains("enum:"), labelDeclaration);
        assertFalse(labelDeclaration.contains("PERSON"), labelDeclaration);
        String parentDeclaration = rendered.substring(rendered.indexOf("parentType:{"),
                rendered.indexOf("},required:[", rendered.indexOf("parentType:{")));
        assertTrue(parentDeclaration.contains("enum:["), parentDeclaration);
        assertTrue(parentDeclaration.contains(Q + "ORGANIZATION" + Q), parentDeclaration);
        assertTrue(rendered.contains("different from its label"));
        assertTrue(rendered.contains("Helios Dynamics is a company."));
        System.out.println("ONTOLOGY_SCHEMA_ADMISSIBILITY parameters=" + tool.getParameters());
        System.out.println("ONTOLOGY_SCHEMA_ADMISSIBILITY labelDeclaration=" + labelDeclaration);
        System.out.println("ONTOLOGY_SCHEMA_ADMISSIBILITY parentDeclaration=" + parentDeclaration);
    }

    @Test
    void unseenSubtypesAndCompanyAreLegalAtEveryPrefixAndPreservedByCodec() throws Exception {
        var tool = tool();
        var c = constraint(tool);
        // Generic unseen labels span different baseline parents. COMPANY is the existing IT assertion.
        Map<String, String> cases = Map.of("RESEARCH_LAB", "ORGANIZATION", "FIELD_STATION", "LOCATION",
                "SENSOR_DEVICE_7", "PRODUCT", "TRAINING_SESSION", "EVENT", "COMPANY", "ORGANIZATION");
        for (var entry : cases.entrySet()) {
            assertFalse(SchemaHierarchyVocabulary.BASE_ENTITY_TYPES.contains(entry.getKey()));
            for (boolean parentFirst : List.of(false, true)) {
                String raw = call(entry.getKey(), entry.getValue(), parentFirst);
                for (int i = 0; i < raw.length(); i++) {
                    String prefix = raw.substring(0, i);
                    assertTrue(c.canExtend(prefix, raw.substring(i, i + 1)), raw + " char=" + i);
                    assertTrue(c.canExtend(prefix, raw.substring(i)), raw + " suffix=" + i);
                }
                assertTrue(c.isAccepting(raw), raw);
                assertTrue(GemmaToolCallCodec.scan(raw, Map.of(tool.getName(), tool)).complete, raw);
                var parsed = ToolCallParser.parse(raw, List.of(tool), ChatTemplate.ToolCallFormat.GEMMA,
                        ChatTemplate.ToolChoice.REQUIRED);
                assertTrue(parsed.isClean(), parsed.getErrors().toString());
                assertEquals(List.of(node(entry.getKey(), entry.getValue())),
                        parsed.getCalls().get(0).getArgs().get("nodeTypes"));
            }
        }
    }

    @Test
    void differentUnseenLabelsCanShareParentInOneUniqueArray() throws Exception {
        var tool = tool();
        var c = constraint(tool);
        String raw = OPEN + "call:submit_node_types" + GemmaToolCallCodec.encode(Map.of("nodeTypes",
                List.of(node("RESEARCH_LAB", "ORGANIZATION"),
                        node("COMPANY", "ORGANIZATION"),
                        node("FIELD_STATION", "LOCATION")))) + CLOSE;
        for (int i = 0; i < raw.length(); i++) {
            assertTrue(c.canExtend(raw.substring(0, i), raw.substring(i, i + 1)), "char=" + i);
            assertTrue(c.canExtend(raw.substring(0, i), raw.substring(i)), "suffix=" + i);
        }
        assertTrue(c.isAccepting(raw));
        assertTrue(ToolCallParser.parse(raw, List.of(tool), ChatTemplate.ToolCallFormat.GEMMA,
                ChatTemplate.ToolChoice.REQUIRED).isClean());
    }

    @Test
    void exactMaskerAllowsUnseenLabelButRejectsItAsParentWithoutReweightingLegalCandidates() throws Exception {
        var tool = tool();
        var c = constraint(tool);
        String labelStart = OPEN + "call:submit_node_types{nodeTypes:[{label:" + Q;
        var masker = new ConstraintMasker(c, 1);
        masker.decodedTextEmitted(labelStart);
        String[] pieces = {"COMPANY", "RESEARCH_LAB", "PERSON", "ORGANIZATION"};
        float[] logits = {1f, 2f, 10f, 9f};
        float[] masked = masker.maskLogitsByDecodedCandidate(logits.clone(), Set.of(), Set.of(),
                id -> pieces[id], id -> labelStart + pieces[id], List.of(OPEN, CLOSE, Q));
        // candidateBudget=1 widens lazily, so separately exercise each candidate at rank one.
        for (int i = 0; i < pieces.length; i++) {
            assertTrue(masker.allowsDecodedText(labelStart + pieces[i], List.of(OPEN, CLOSE, Q)), pieces[i]);
            float[] ranked = {1f, 2f, 3f, 4f};
            ranked[i] = 20f;
            float[] admitted = masker.maskLogitsByDecodedCandidate(ranked, Set.of(), Set.of(),
                    id -> pieces[id], id -> labelStart + pieces[id], List.of(OPEN, CLOSE, Q));
            assertEquals(20f, admitted[i], "Legal top-ranked candidate must survive: " + pieces[i]);
        }
        assertEquals(10f, masked[2], "Masking does not force a new subtype over a legal baseline label");
        for (String label : List.of("COMPANY", "RESEARCH_LAB", "SENSOR_DEVICE_7")) {
            assertFalse(c.isAccepting(call(label, label, false)), "Unseen parent must not pass: " + label);
            String parentStart = labelStart + label + Q + ",parentType:" + Q;
            assertFalse(c.canExtend(parentStart, label));
        }
        assertFalse(c.isAccepting(call("lower_case", "ORGANIZATION", false)));
        assertFalse(c.isAccepting(call("A".repeat(49), "ORGANIZATION", false)));
        // JSON Schema permits this structurally; the semantic unifier must discard it.
        assertTrue(c.isAccepting(call("PERSON", "PERSON", false)));
    }
}
