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

package ai.kompile.staging.cli.archive;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Parent command for archive operations.
 * Provides subcommands for creating, importing, downloading, and managing Kompile archives.
 */
@Component
@Command(
    name = "archive",
    description = "Manage Kompile archives (.karch) for model distribution",
    mixinStandardHelpOptions = true,
    subcommands = {
        ArchiveExportCommand.class,
        ArchiveImportCommand.class,
        ArchiveDownloadCommand.class,
        ArchiveListCommand.class,
        ArchiveUpdateCommand.class
    }
)
public class ArchiveCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Kompile Archive Management");
        System.out.println();
        System.out.println("Use --help to see available subcommands");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  kompile-staging archive export --models=bge-base-en-v1.5 --output=my-models-1.0.0.karch --version=1.0.0");
        System.out.println("  kompile-staging archive import --file=my-models-1.0.0.karch --verify");
        System.out.println("  kompile-staging archive download --url=https://example.com/models.karch --resume");
        System.out.println("  kompile-staging archive list");
        System.out.println("  kompile-staging archive check-updates");
        System.out.println("  kompile-staging archive update --all");
        return 0;
    }
}
