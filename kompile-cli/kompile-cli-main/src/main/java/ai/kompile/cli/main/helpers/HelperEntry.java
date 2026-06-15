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

package ai.kompile.cli.main.helpers;

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "helper",
        description = "Entry point for higher level helpers containing common patterns for pipeline creation",
        subcommands = {
                ClassifierHelper.class
        }, mixinStandardHelpOptions = false)
public class HelperEntry implements Callable<Integer>  {

    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new HelperEntry());
        commandLine.usage(System.err);
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new HelperEntry());
        commandLine.usage(System.err);
        return 0;
    }
}
