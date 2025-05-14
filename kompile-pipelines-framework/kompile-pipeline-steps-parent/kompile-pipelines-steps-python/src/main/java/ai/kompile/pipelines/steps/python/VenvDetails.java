package ai.kompile.pipelines.steps.python;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class VenvDetails {
    private String id;
    private String path;
    private String version;
}