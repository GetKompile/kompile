package ai.konduit.config.generator.executors;

import ai.konduit.config.generator.runners.ConfigGeneratorRunner;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class RunnerExecutor {

	private final String TRACING_AGENT_OPTION = "-agentlib:native-image-agent=config-output-dir=/META-INF/native-image/";

	public void execute(ConfigGeneratorRunner basedRunner) throws IOException, InterruptedException, TimeoutException {

		ProcessExecutor processExecutor = new ProcessExecutor(getJavaPath(), TRACING_AGENT_OPTION, "-cp", getClasspath(), basedRunner.getClass().getName());
		processExecutor.execute();
	}

	private String getJavaPath() {

		String separator = System.getProperty("file.separator");
		return System.getProperty("java.home") + separator + "bin" + separator + "java";
	}

	private String getClasspath() {

		return System.getProperty("java.class.path");
	}
}
