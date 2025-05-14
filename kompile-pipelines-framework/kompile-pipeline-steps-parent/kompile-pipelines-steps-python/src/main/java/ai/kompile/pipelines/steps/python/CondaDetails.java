package ai.kompile.pipelines.steps.python;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(fluent = true)
@NoArgsConstructor
@AllArgsConstructor
public class CondaDetails {
    private String id;
    private String path;
    private String version;
    private List<PythonDetails> environments;
}