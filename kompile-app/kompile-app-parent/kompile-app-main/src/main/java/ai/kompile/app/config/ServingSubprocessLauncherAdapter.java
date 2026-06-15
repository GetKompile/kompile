/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.config;

import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.services.subprocess.ServingSubprocessLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Adapts {@link ServingSubprocessLauncher} to the {@link LlmModelController.SubprocessLauncher}
 * interface so the controller can start/stop the serving subprocess without a direct
 * module dependency.
 */
@Configuration(proxyBeanMethods = false)
public class ServingSubprocessLauncherAdapter {

    @Bean
    public LlmModelController.SubprocessLauncher subprocessLauncher(
            ServingSubprocessLauncher launcher) {
        return new LlmModelController.SubprocessLauncher() {
            @Override
            public String loadModel(String modelId, String modelPath,
                                    Map<String, Object> options) throws Exception {
                return launcher.loadModel(modelId, modelPath, options);
            }

            @Override
            public void stop() {
                launcher.stop();
            }

            @Override
            public String getStatus() throws Exception {
                return launcher.getStatus();
            }

            @Override
            public boolean isRunning() {
                return launcher.isRunning();
            }

            @Override
            public int getServingPort() {
                return launcher.getServingPort();
            }
        };
    }
}
