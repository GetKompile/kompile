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

package ai.kompile.staging.cli;

import ai.kompile.staging.cli.archive.ArchiveCommand;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Main CLI entry point for the model staging service.
 */
@Component
@Command(
    name = "kompile-staging",
    description = "Kompile Model Staging Service CLI",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = {
        DownloadCommand.class,
        ConvertCommand.class,
        PromoteCommand.class,
        ListCommand.class,
        ExportCommand.class,
        ImportCommand.class,
        PipelineCommand.class,
        ArchiveCommand.class
    }
)
public class ModelStagingCLI implements Callable<Integer> {

    @Override
    public Integer call() {
        // Print help if no subcommand specified
        System.out.println("Kompile Model Staging Service");
        System.out.println();
        System.out.println("Use --help to see available commands");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  kompile-staging download --source=huggingface --repo=BAAI/bge-base-en-v1.5");
        System.out.println("  kompile-staging convert --input=model.onnx --output=model.sdz");
        System.out.println("  kompile-staging promote --model=bge-base-en-v1.5");
        System.out.println("  kompile-staging list");
        System.out.println("  kompile-staging export --models=bge-base-en-v1.5 --output=bundle.tar.gz");
        System.out.println("  kompile-staging import --bundle=bundle.tar.gz");
        System.out.println("  kompile-staging archive export --models=bge-base-en-v1.5 --output=models.karch --version=1.0.0");
        System.out.println("  kompile-staging archive update --all");
        return 0;
    }
}
