package ai.kompile.kvcache.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointInfo {
    private String id;
    private String label;
    private long createdAt;
    private int tokenCount;
    private long sizeBytes;
    private boolean onDisk;
    private String diskPath;
}
