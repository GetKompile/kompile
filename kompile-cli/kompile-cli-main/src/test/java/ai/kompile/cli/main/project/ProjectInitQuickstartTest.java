/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.MainCommand;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the one-command end-to-end wiring on {@code kompile project init}:
 *  - the new --serve/--crawl/--push/--keep-running/--serve-port/--staging-port/--no-staging
 *    options are exposed and parse correctly, and
 *  - the implication contract (push -> crawl -> serve) holds.
 *
 * These are self-contained: they only inspect the picocli model and the pure
 * {@link ProjectServiceCommand#quickstartPlan(boolean, boolean, boolean)} helper, so no
 * services are started and no network calls are made.
 */
class ProjectInitQuickstartTest {

    private static CommandLine initCommand() {
        CommandLine root = new CommandLine(new MainCommand());
        CommandLine project = root.getSubcommands().get("project");
        assertNotNull(project, "project subcommand should be registered");
        CommandLine init = project.getSubcommands().get("init");
        assertNotNull(init, "project init subcommand should be registered");
        return init;
    }

    @Test
    void initExposesQuickstartOptions() {
        CommandSpec spec = initCommand().getCommandSpec();
        for (String opt : new String[]{"--serve", "--crawl", "--push", "--keep-running",
                "--serve-port", "--staging-port", "--no-staging"}) {
            assertNotNull(spec.findOption(opt), "init should expose " + opt);
        }
        assertEquals("8080", spec.findOption("--serve-port").defaultValue());
        assertEquals("8090", spec.findOption("--staging-port").defaultValue());
    }

    @Test
    void initParsesQuickstartFlags() {
        // Parse from the root command (parsing a sub-CommandLine in isolation is unsupported),
        // then navigate into the project -> init subcommand parse result. parseArgs does not
        // execute the command, so no services are started.
        CommandLine root = new CommandLine(new MainCommand());
        ParseResult initPr = root.parseArgs("project", "init", "--push", "--serve-port", "9090", "--keep-running")
                .subcommand()   // project
                .subcommand();  // init
        assertTrue(initPr.matchedOptionValue("--push", Boolean.FALSE));
        assertTrue(initPr.matchedOptionValue("--keep-running", Boolean.FALSE));
        assertEquals(9090, (int) initPr.matchedOptionValue("--serve-port", 8080));
    }

    @Test
    void pushImpliesCrawlImpliesServe() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(false, false, true);
        assertTrue(p.serve());
        assertTrue(p.crawl());
        assertTrue(p.push());
    }

    @Test
    void crawlImpliesServeButNotPush() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(false, true, false);
        assertTrue(p.serve());
        assertTrue(p.crawl());
        assertFalse(p.push());
    }

    @Test
    void serveAloneDoesNotImplyCrawlOrPush() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(true, false, false);
        assertTrue(p.serve());
        assertFalse(p.crawl());
        assertFalse(p.push());
    }

    @Test
    void noFlagsMeansNoQuickstart() {
        ProjectServiceCommand.QuickstartPlan p = ProjectServiceCommand.quickstartPlan(false, false, false);
        assertFalse(p.serve());
        assertFalse(p.crawl());
        assertFalse(p.push());
    }
}
