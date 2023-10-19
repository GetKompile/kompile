/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.Info;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "graalvm",mixinStandardHelpOptions = false)
public class InstallGraalvm implements Callable<Integer> {

    public final static String DOWNLOAD_URL = "https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-22.3.0/graalvm-ce-java11-linux-amd64-22.3.0.tar.gz";
    //for other platforms see: https://github.com/graalvm/graalvm-ce-builds/releases/tag/vm-20.3.6
    public final static String FILE_NAME = "graalvm-ce-java11-linux-amd64-22.3.0.tar.gz";

    public InstallGraalvm() {
    }

    @Override
    public Integer call() throws Exception {
        File graalVm = Info.graalvmDirectory();
        if(graalVm.exists() && graalVm.list().length > 0) {
            System.out.println("Graalvm already installed. Skipping. If there is a problem with your install, please call ./kompile uninstall graalvm");
        }

        File archive = InstallMain.downloadAndLoadFrom(DOWNLOAD_URL,FILE_NAME,false);
        if(archive == null) {
            System.err.println("File archive for folder " + graalVm + " named " + FILE_NAME + " appears to already exist or be corrupt. Please delete and try again.");
            return 1;
        }
        archive.getParentFile().mkdirs();
        ArchiveUtils.unzipFileTo(archive.getAbsolutePath(),graalVm.getAbsolutePath(),true);
        //extracts to a directory, move everything to parent directory
        File graalVmDir = new File(Info.graalvmDirectory(),"graalvm-ce-java11-22.3.0");
        FileUtils.copyDirectory(graalVmDir,Info.graalvmDirectory());
        FileUtils.deleteDirectory(graalVmDir);
        File executables = new File(Info.graalvmDirectory(),"bin");
        for(File f : executables.listFiles()) {
            f.setExecutable(true);
        }

        File executableLibGu = new File(Info.graalvmDirectory(),"lib/installer/bin");
        for(File f : executableLibGu.listFiles()) {
            f.setExecutable(true);
        }

        int  exitValue =  new ProcessExecutor().environment(System.getenv())
                .command(Arrays.asList(executableLibGu + "/gu", "install" ,
                        "native-image"))
                .readOutput(true)
                .redirectOutput(System.out)
                .start().getFuture().get().getExitValue();
        if(exitValue == 0)
            System.out.println("Installed graalvm at " + Info.graalvmDirectory());

        return exitValue;
    }
}
