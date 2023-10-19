package ai.konduit.config.generator;

import ai.konduit.config.generator.executors.RunnerExecutor;
import ai.konduit.config.generator.runners.Nd4jRunner;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class Application {

	public static void main(String[] args) throws IOException, InterruptedException, TimeoutException {

		RunnerExecutor runnerExecutor = new RunnerExecutor();
		runnerExecutor.execute(new Nd4jRunner());
	}
}
