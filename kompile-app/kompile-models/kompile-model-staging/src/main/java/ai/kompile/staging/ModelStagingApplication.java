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

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.staging.cli.ModelStagingCLI;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
@SpringBootApplication(scanBasePackages = {"ai.kompile.staging", "ai.kompile.modelmanager"})
public class ModelStagingApplication implements CommandLineRunner, ExitCodeGenerator {

    private final IFactory factory;
    private final ModelStagingCLI cli;
    private int exitCode;

    public ModelStagingApplication(IFactory factory, ModelStagingCLI cli) {
        this.factory = factory;
        this.cli = cli;
    }

    public static void main(String[] args) {
        // Managed config is intentionally shared by the UI and CLI rather than owned by
        // Spring. Bridge the standard application argument before any config manager is
        // constructed so this standalone component reads <project>/config consistently.
        applyProjectDataDir(args);

        // Resolve the same canonical side-loaded lib/ directory used by every other
        // distributable process before Spring or any ND4J class can initialize.
        NativeLibraryResolver.bootstrapOrThrow();

        // Check if running as CLI or REST server
        if (isCliMode(args)) {
            System.exit(SpringApplication.exit(createCliApplication().run(args)));
        } else {
            // Run as REST server
            SpringApplication.run(ModelStagingApplication.class, args);
        }
    }

    static boolean isCliMode(String[] args) {
        return args != null && args.length > 0 && !args[0].startsWith("--server");
    }

    static SpringApplication createCliApplication() {
        // Native images are AOT-compiled as servlet applications because the same executable
        // also owns the REST/MCP server. Switching that image to WebApplicationType.NONE at
        // runtime leaves servlet MVC beans without a ServletContext. Keep the AOT context type
        // stable for CLI/bootstrap invocations, bind only an ephemeral loopback port, and
        // disable MCP transport for the lifetime of this short-lived subprocess.
        System.setProperty("server.address", "127.0.0.1");
        System.setProperty("server.port", "0");
        System.setProperty("kompile.staging.mcp.enabled", "false");

        SpringApplication application = new SpringApplication(ModelStagingApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        return application;
    }

    static void applyProjectDataDir(String[] args) {
        if (args == null) {
            return;
        }
        applySystemProperty(args, "--kompile.data.dir=", "kompile.data.dir");
        applySystemProperty(args, "--kompile.staging.models-dir=", "kompile.staging.models-dir");
    }

    private static void applySystemProperty(String[] args, String prefix, String property) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).trim();
                if (!value.isEmpty()) {
                    System.setProperty(property, value);
                }
            }
        }
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length > 0 && !args[0].startsWith("--server")) {
            // Spring consumes project-local infrastructure settings; subcommands should
            // receive only their own ABI arguments in both native and executable-JAR tiers.
            String[] commandArgs = java.util.Arrays.stream(args)
                    .filter(arg -> arg == null || !arg.startsWith("--kompile."))
                    .toArray(String[]::new);
            exitCode = new CommandLine(cli, factory).execute(commandArgs);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

}
