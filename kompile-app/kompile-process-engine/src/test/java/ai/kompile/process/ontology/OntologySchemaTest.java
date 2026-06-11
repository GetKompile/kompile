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

package ai.kompile.process.ontology;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OntologySchema}.
 */
class OntologySchemaTest {

    @Test
    void noArgConstructor_createsEmptyInstance() {
        OntologySchema schema = new OntologySchema();
        assertThat(schema.getId()).isNull();
        assertThat(schema.getName()).isNull();
        assertThat(schema.getVersion()).isZero();
        assertThat(schema.getEntityTypes()).isNull();
    }

    @Test
    void builder_setsAllBasicFields() {
        Instant now = Instant.now();
        OntologySchema schema = OntologySchema.builder()
                .id("schema-001")
                .name("FP&A CPG Channel v3.1")
                .version(3)
                .templateId("FP&A_CPG_Channel v3.1")
                .createdAt(now)
                .updatedAt(now)
                .updatedBy("user-ontology-admin")
                .build();

        assertThat(schema.getId()).isEqualTo("schema-001");
        assertThat(schema.getName()).isEqualTo("FP&A CPG Channel v3.1");
        assertThat(schema.getVersion()).isEqualTo(3);
        assertThat(schema.getTemplateId()).isEqualTo("FP&A_CPG_Channel v3.1");
        assertThat(schema.getCreatedAt()).isEqualTo(now);
        assertThat(schema.getUpdatedAt()).isEqualTo(now);
        assertThat(schema.getUpdatedBy()).isEqualTo("user-ontology-admin");
    }

    @Test
    void builder_withEntityTypes_setsListField() {
        EntityTypeDefinition entityType = EntityTypeDefinition.builder()
                .name("ChannelTaxonomy")
                .description("Hierarchical channel taxonomy")
                .confidence(0.95)
                .build();

        OntologySchema schema = OntologySchema.builder()
                .id("schema-002")
                .entityTypes(List.of(entityType))
                .build();

        assertThat(schema.getEntityTypes()).hasSize(1);
        assertThat(schema.getEntityTypes().get(0).getName()).isEqualTo("ChannelTaxonomy");
    }

    @Test
    void builder_withMetadata_preservesMap() {
        OntologySchema schema = OntologySchema.builder()
                .id("schema-003")
                .metadata(Map.of("domain", "FP&A", "industry", "CPG"))
                .build();

        assertThat(schema.getMetadata()).containsEntry("domain", "FP&A");
        assertThat(schema.getMetadata()).containsEntry("industry", "CPG");
    }

    @Test
    void builder_withRelationshipTypes_setsListField() {
        RelationshipTypeDefinition relType = RelationshipTypeDefinition.builder()
                .type("FEEDS_INTO")
                .description("Source data feeds into target")
                .build();

        OntologySchema schema = OntologySchema.builder()
                .id("schema-004")
                .relationshipTypes(List.of(relType))
                .build();

        assertThat(schema.getRelationshipTypes()).hasSize(1);
        assertThat(schema.getRelationshipTypes().get(0).getType()).isEqualTo("FEEDS_INTO");
    }

    @Test
    void builder_withGlobalRules_setsListField() {
        ValidationRule rule = ValidationRule.builder()
                .name("non_null_revenue")
                .description("Revenue must not be null")
                .build();

        OntologySchema schema = OntologySchema.builder()
                .id("schema-005")
                .globalRules(List.of(rule))
                .build();

        assertThat(schema.getGlobalRules()).hasSize(1);
        assertThat(schema.getGlobalRules().get(0).getName()).isEqualTo("non_null_revenue");
    }

    @Test
    void equalsAndHashCode_symmetry() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        OntologySchema a = OntologySchema.builder().id("s1").name("Test").version(1).createdAt(t).build();
        OntologySchema b = OntologySchema.builder().id("s1").name("Test").version(1).createdAt(t).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsClassName() {
        OntologySchema schema = OntologySchema.builder().id("s2").build();
        assertThat(schema.toString()).contains("OntologySchema");
    }
}
