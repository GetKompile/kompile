package ai.kompile.graphchangetracking.domain;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphMutationRecord {

    private Long id;

    private String mutationId;

    private String mutationType;

    private String entityKind;

    private String entityId;

    private Long factSheetId;

    private String triggerSource;

    private String triggerId;

    private String actorId;

    private String actorType;

    private String snapshotBefore;

    private String snapshotAfter;

    private String changesetId;

    private LocalDateTime occurredAt;

    public void initDefaults() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (mutationId == null) {
            mutationId = UUID.randomUUID().toString();
        }
    }
}
