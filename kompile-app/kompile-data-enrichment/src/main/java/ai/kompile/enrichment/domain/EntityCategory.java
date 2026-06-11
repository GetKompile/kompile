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
package ai.kompile.enrichment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "entity_categories", indexes = {
        @Index(name = "idx_cat_fact_sheet", columnList = "factSheetId"),
        @Index(name = "idx_cat_category_id", columnList = "categoryId"),
        @Index(name = "idx_cat_parent", columnList = "parentCategoryId")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EntityCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private Long factSheetId;

    @Column(nullable = false, length = 128)
    private String categoryId;

    @Column(nullable = false)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String parentCategoryId;

    @Column(length = 7)
    private String color;

    @Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String source = "USER_DEFINED";

    @Builder.Default
    private boolean active = true;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
