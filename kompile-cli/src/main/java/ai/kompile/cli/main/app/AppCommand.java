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

package ai.kompile.cli.main.app;

import ai.kompile.cli.main.a2a.A2ACommand;
import ai.kompile.cli.main.graph.GraphCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Top-level command group for interacting with a running kompile-app instance.
 * All subcommands here communicate with a kompile-app server via its REST API.
 *
 * <p>Connection is auto-discovered or can be specified with --url / --port on
 * each subcommand.</p>
 */
@Command(
        name = "app",
        description = "Interact with a running kompile-app instance.%n%n" +
                "All subcommands communicate with a kompile-app server via REST.%n" +
                "Connection is auto-discovered, or specify --url / --port on any subcommand.%n%n" +
                "Document management:%n" +
                "  ingest        Upload and manage document ingestion%n" +
                "  index         Index and vector store management%n" +
                "  crawl         Crawl web, file, and email sources%n" +
                "  jobs          Indexing job history and logs%n%n" +
                "Query and retrieval:%n" +
                "  rag-pipeline  Create and execute RAG pipelines%n" +
                "  graph         Knowledge graph operations%n%n" +
                "Administration:%n" +
                "  setup         Configure and check app readiness%n" +
                "  schedule      Manage scheduled jobs%n" +
                "  subprocess    Inspect subprocess execution%n" +
                "  a2a           Agent-to-Agent protocol management%n",
        subcommands = {
                IngestCommand.class,
                IndexCommand.class,
                JobsCommand.class,
                CrawlCommand.class,
                SetupCommand.class,
                RagPipelineCommand.class,
                ScheduleCommand.class,
                SubprocessCommand.class,
                GraphCommand.class,
                A2ACommand.class
        },
        mixinStandardHelpOptions = true
)
public class AppCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }
}
