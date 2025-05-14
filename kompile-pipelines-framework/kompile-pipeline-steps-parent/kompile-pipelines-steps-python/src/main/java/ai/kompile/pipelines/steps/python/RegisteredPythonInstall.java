package ai.kompile.pipelines.steps.python;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
@NoArgsConstructor
@AllArgsConstructor
public class RegisteredPythonInstall {
    private PythonPathUtils.PythonType pythonType;
    private String path;
    private String version;
}
