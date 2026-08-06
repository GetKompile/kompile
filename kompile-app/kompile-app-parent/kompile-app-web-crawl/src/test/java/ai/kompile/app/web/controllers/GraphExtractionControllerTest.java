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
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.GraphExtractionConfigService;
import ai.kompile.app.services.GraphExtractionConfigService.GraphExtractionConfig;
import ai.kompile.app.services.GraphSchemaPresetService;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphExtractionControllerTest {

    @Test
    void applyingPresetPersistsTheFullCanonicalSchema() {
        GraphExtractionConfigService configService = mock(GraphExtractionConfigService.class);
        GraphSchemaPresetService presetService = mock(GraphSchemaPresetService.class);
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person",
                                List.of(new PropertyType("role", "String"))),
                        new NodeType("ROLE", "A business role", null)),
                List.of(new RelationshipType(
                        "HAS_ROLE", "Person has role",
                        List.of(new PropertyType("since", "String")),
                        List.of("serves_as"))),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        when(presetService.getSchema("finance-v1")).thenReturn(Optional.of(schema));
        when(configService.getConfig()).thenReturn(GraphExtractionConfig.defaults());
        when(configService.updateConfig(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GraphExtractionController controller =
                new GraphExtractionController(configService, null, presetService);

        ResponseEntity<?> response = controller.applySchemaPreset("finance-v1");

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<GraphExtractionConfig> captor =
                ArgumentCaptor.forClass(GraphExtractionConfig.class);
        verify(configService).updateConfig(captor.capture());
        GraphExtractionConfig update = captor.getValue();

        assertNotNull(update.standardizedSchema);
        assertEquals(List.of("PERSON", "ROLE"), update.entityTypes);
        assertEquals(List.of("HAS_ROLE"), update.relationshipTypes);
        assertEquals("A person", update.standardizedSchema.getNodeTypes().get(0).getDescription());
        assertEquals("role", update.standardizedSchema.getNodeTypes().get(0)
                .getProperties().get(0).getName());
        assertEquals(List.of("serves_as"), update.standardizedSchema
                .getRelationshipTypes().get(0).getAliases());
        assertEquals(List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"),
                update.validationPolicy.effectiveRelationPatterns());
    }
}
