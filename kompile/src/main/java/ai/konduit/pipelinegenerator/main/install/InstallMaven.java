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
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "maven",mixinStandardHelpOptions = false)
public class InstallMaven implements Callable<Integer> {

    public final static String MAVEN_URL = "https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz";
    public final static String FILE_NAME = "apache-maven-3.8.6-bin.tar.gz";

    public InstallMaven() {
    }

    @Override
    public Integer call() throws Exception {
        File mavenDir = Info.mavenDirectory();
        if(mavenDir.exists() && mavenDir.list().length > 0) {
            System.out.println("Maven already installed. Skipping. If there is a problem with your install, please call ./kompile uninstall maven");
        }
        File destination = new File(mavenDir,FILE_NAME);
        File archive = InstallMain.downloadAndLoadFrom(MAVEN_URL,FILE_NAME,false);
        ArchiveUtils.unzipFileTo(archive.getAbsolutePath(),destination.getAbsolutePath(),true);
        //extracts to a directory, move everything to parent directory
        File mavenDirectory = new File(Info.mavenDirectory(),"apache-maven-3.8.6-bin.tar.gz/apache-maven-3.8.6/");
        FileUtils.copyDirectory(mavenDirectory,Info.mavenDirectory());
        FileUtils.deleteDirectory(mavenDirectory);
        File mavenExecutable = new File(Info.mavenDirectory(),"bin/mvn");
        if(!mavenExecutable.exists()) {
            System.err.println("No maven executable found. Failed to install. Exiting.");
            System.exit(1);
        } else {
            if(!Files.isExecutable(mavenExecutable.toPath())) {
               if(!mavenExecutable.setExecutable(true)) {
                   System.err.println("Failed to set maven executable after download. Failed install due to permissions. Please ensure appropriate permissions on the current directory. Exiting.");
                   System.exit(1);
               } else {
                   System.out.println("Maven executable configured. Please ensure you add "  + new File(mavenDirectory.getAbsolutePath(),"bin") + " to your path.");
               }

            }
        }

        System.out.println("Installed maven at " + Info.mavenDirectory());

        return 0;
    }
}
