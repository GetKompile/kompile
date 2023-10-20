/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.config.generator.executors;

import ai.konduit.config.generator.runners.ConfigGeneratorRunner;
import ai.konduit.config.generator.runners.Nd4jRunner;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class RunnerExecutor {

	private static final String TRACING_AGENT_OPTION = "-agentlib:native-image-agent=config-output-dir=/META-INF/native-image/";

	public static void main(String[] args) throws IOException, InterruptedException, TimeoutException {

		RunnerExecutor runnerExecutor = new RunnerExecutor();
		runnerExecutor.execute(new Nd4jRunner());
	}

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
