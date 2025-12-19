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

package ai.kompile.staging;

import ai.kompile.staging.cli.ModelStagingCLI;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Main Spring Boot application for the model staging service.
 *
 * This application provides both a REST API and CLI for:
 * - Downloading models from HuggingFace, GitHub, or HTTP sources
 * - Converting models to SameDiff format
 * - Staging and promoting models to production
 * - Exporting/importing model bundles for air-gap transfers
 *
 * Run as REST API:
 *   java -jar kompile-model-staging.jar
 *
 * Run as CLI:
 *   java -jar kompile-model-staging.jar download --source=huggingface --repo=BAAI/bge-base-en-v1.5
 */
@SpringBootApplication
public class ModelStagingApplication implements CommandLineRunner, ExitCodeGenerator {

    private final IFactory factory;
    private final ModelStagingCLI cli;
    private int exitCode;

    public ModelStagingApplication(IFactory factory, ModelStagingCLI cli) {
        this.factory = factory;
        this.cli = cli;
    }

    public static void main(String[] args) {
        // Check if running as CLI or REST server
        if (args.length > 0 && !args[0].startsWith("--server")) {
            // Run as CLI
            System.exit(SpringApplication.exit(SpringApplication.run(
                    ModelStagingApplication.class, args)));
        } else {
            // Run as REST server
            SpringApplication.run(ModelStagingApplication.class, args);
        }
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length > 0 && !args[0].startsWith("--server")) {
            // Execute CLI command
            exitCode = new CommandLine(cli, factory).execute(args);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Bean
    public CommandLine.IFactory picocliFactory(ApplicationContext applicationContext) {
        return new picocli.spring.PicocliSpringFactory(applicationContext);
    }
}
