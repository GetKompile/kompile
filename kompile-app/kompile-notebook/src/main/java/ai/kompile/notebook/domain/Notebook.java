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

package ai.kompile.notebook.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Notebook is a logical research workspace that groups sources (Facts) and user Notes together.
 *
 * Design note: FactSheet = "physical index scope" (the actual vector/keyword index).
 * Notebook = "curated research workspace" that references one or more FactSheets' Facts.
 * A Fact can belong to multiple notebooks via NotebookSource (M:N join).
 */
@Entity
@Table(name = "notebooks", indexes = {
    @Index(name = "idx_notebook_name", columnList = "name"),
    @Index(name = "idx_notebook_archived", columnList = "archived"),
    @Index(name = "idx_notebook_created", columnList = "createdAt"),
    @Index(name = "idx_notebook_owner", columnList = "ownerUserId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notebook {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /** User-visible name for this notebook. */
    @Column(nullable = false, length = 255)
    private String name;

    /** Optional description of what this notebook covers. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Optional emoji or icon name for UI display (e.g., "book", "🔬"). */
    @Column(length = 50)
    @Builder.Default
    private String icon = "book";

    /** Hex color for UI display (e.g., "#1976d2"). */
    @Column(length = 7)
    @Builder.Default
    private String color = "#1976d2";

    /** Whether this notebook is archived (hidden from default views). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean archived = false;

    /**
     * Optional owner/user identifier for future multi-user support.
     * Null means single-tenant (current default deployment mode).
     */
    @Column(length = 255)
    private String ownerUserId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Sources (Facts) that belong to this notebook. */
    @OneToMany(mappedBy = "notebook", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<NotebookSource> sources = new ArrayList<>();

    /** Notes authored within this notebook. */
    @OneToMany(mappedBy = "notebook", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Note> notes = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Return source count without loading the collection. */
    public int getSourceCount() {
        return sources != null ? sources.size() : 0;
    }

    /** Return note count without loading the collection. */
    public int getNoteCount() {
        return notes != null ? notes.size() : 0;
    }
}
