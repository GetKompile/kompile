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

package ai.kompile.cli.main.install;

import ai.kompile.cli.main.Info;
import org.nd4j.common.base.Preconditions;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "python",mixinStandardHelpOptions = false)
public class InstallPython implements Callable<Integer> {

    public final static String PYTHON_URL = "https://repo.anaconda.com/miniconda/Miniconda3-py39_4.9.2-Linux-x86_64.sh";
    public final static String FILE_NAME = "Miniconda3-py39_4.9.2-Linux-x86_64.sh";


    public InstallPython() {
    }

    @Override
    public Integer call() throws Exception {
        File pythonDir = Info.pythonDirectory();
        String[] pythonDirContents = pythonDir.exists() ? pythonDir.list() : null;
        if(pythonDirContents != null && pythonDirContents.length > 0) {
            System.out.println("Python already installed. Skipping. If there is a problem with your install, please call ./kompile uninstall python");
            return 0;
        }

        File newFile = InstallMain.downloadAndLoadFrom(PYTHON_URL,FILE_NAME,false);
        Preconditions.checkState(newFile.setExecutable(true),"Unable to set file executable. Please ensure your user has proper permissions for installing anaconda.");
        int exitValue =  new ProcessExecutor().environment(System.getenv())
                .command(Arrays.asList(newFile.getAbsolutePath(), "-b","-p" ,pythonDir.getAbsolutePath()))
                .readOutput(true)
                .redirectOutput(System.out)
                .start().getFuture().get().getExitValue();
        Preconditions.checkState(exitValue == 0,"Anaconda failed to install.");
        exitValue =  new ProcessExecutor().environment(System.getenv())
                .command(Arrays.asList(pythonDir.getAbsolutePath() + "/bin/conda", "install" ,
                        "Cython","numpy"))
                .readOutput(true)
                .redirectOutput(System.out)
                .start().getFuture().get().getExitValue();


        return exitValue;

    }
}
