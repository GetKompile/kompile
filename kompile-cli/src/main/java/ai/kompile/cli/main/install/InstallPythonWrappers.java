/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.install;

import ai.kompile.cli.main.Info;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "install-python-wrappers",mixinStandardHelpOptions = false,description = "Installs python wrappers for tensorflow and pytorch for the kompile SDK. Note this requires having already run ./kompile build generate-image-and-sdk and having the sdk installed in order to work.")
public class InstallPythonWrappers implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new InstallKompileComponents());
        File kompileDir = new File(Info.homeDirectory(),"kompile");
        int exitCode = commandLine.execute("--kompileLocation=" + kompileDir.getAbsolutePath());
        exitCode  = 0;
        File pythonDir = new File(Info.homeDirectory(),"python");
        File binDir = new File(pythonDir,"bin");
        File pythonExec = new File(binDir,"python");
        for(String library : new String[]{"kompile_pytorch","kompile_tensorflow"}) {
            exitCode =  new ProcessExecutor().environment(System.getenv())
                    .command(Arrays.asList(pythonExec.getAbsolutePath() ,"setup.py", "install"))
                    .directory(new File(kompileDir,File.separator + library))
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .start().getFuture().get().getExitValue();

        }

        return exitCode;
    }
}
