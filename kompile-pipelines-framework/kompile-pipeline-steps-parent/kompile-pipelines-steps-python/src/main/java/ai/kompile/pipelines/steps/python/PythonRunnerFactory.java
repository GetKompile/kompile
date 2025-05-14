package ai.kompile.pipelines.steps.python;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;

public class PythonRunnerFactory implements PipelineStepRunnerFactory {
    @Override
    public String getRunnerType() {
        return PythonRunnerConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new PythonRunner();
    }
}