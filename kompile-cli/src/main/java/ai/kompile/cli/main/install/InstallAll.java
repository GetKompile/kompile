/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.install;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "all",mixinStandardHelpOptions = false)
public class InstallAll implements Callable<Integer> {

    public InstallAll() {
    }

    @Override
    public Integer call() throws Exception {
        int exit = new InstallGraalvm().call();
        if(exit != 0) {
            System.err.println("Failed to install graalvm.");
            return exit;
        }
        exit = new InstallMaven().call();
        if(exit != 0) {
            System.err.println("Failed to install maven.");
            return exit;
        }
        exit = new InstallPython().call();
        if(exit != 0) {
            System.err.println("Failed to install python.");
            return exit;
        }
        exit = new InstallHeaders().call();
        if(exit != 0) {
            System.err.println("Failed to install headers.");
            return exit;
        }
        return exit;
    }


    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new InstallAll());
        System.exit(commandLine.execute(args));
    }

}
