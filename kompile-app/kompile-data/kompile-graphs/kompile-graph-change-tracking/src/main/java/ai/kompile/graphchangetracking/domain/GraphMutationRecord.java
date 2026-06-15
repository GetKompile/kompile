package ai.kompile.graphchangetracking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "graph_mutation_records", indexes = {
        @Index(name = "idx_gmr_entity", columnList = "entityKind, entityId"),
        @Index(name = "idx_gmr_factsheet_time", columnList = "factSheetId, occurredAt"),
        @Index(name = "idx_gmr_changeset", columnList = "changesetId"),
        @Index(name = "idx_gmr_trigger_time", columnList = "triggerSource, occurredAt"),
        @Index(name = "idx_gmr_occurred_at", columnList = "occurredAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphMutationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, unique = true, nullable = false)
    private String mutationId;

    @Column(length = 32, nullable = false)
    private String mutationType;

    @Column(length = 8, nullable = false)
    private String entityKind;

    @Column(length = 36, nullable = false)
    private String entityId;

    @Column
    private Long factSheetId;

    @Column(length = 128)
    private String triggerSource;

    @Column(length = 128)
    private String triggerId;

    @Column(length = 128)
    private String actorId;

    @Column(length = 32)
    private String actorType;

    @Column(columnDefinition = "TEXT")
    private String snapshotBefore;

    @Column(columnDefinition = "TEXT")
    private String snapshotAfter;

    @Column(length = 36)
    private String changesetId;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (mutationId == null) {
            mutationId = java.util.UUID.randomUUID().toString();
        }
    }
}
