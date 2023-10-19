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
import ai.konduit.pipelinegenerator.main.util.EnvironmentFile;
import ai.konduit.pipelinegenerator.main.util.OpenBlasEmbeddedDownloader;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "openblas-install",mixinStandardHelpOptions = false,description = "Installs openblas for a particular architecture the kompile home directory.")
public class OpenBlasInstaller implements Callable<Integer> {

    @CommandLine.Option(names = {"--os"},
            description = "The operating system to download for. Valid values: linux,android",required = true)

    private String os;
    @CommandLine.Option(names = {"--architecture"},
            description = "The architecture to download for. Valid values: arm32,arm64",required = true)

    private String architecture;
    @CommandLine.Option(names = {"--javaCppOpenBlasVersion"},
            description = "The openblas version to use. Usually should not change. Value: 0.3.19-1.5.7",required = false)

    private String javaCppOpenBlasVersion = "0.3.19-1.5.7";
    @CommandLine.Option(names = {"--forceDownload"},
            description = "Whether to force redownload or not.",required = false)

    private boolean forceDownload;
    @Override
    public Integer call() throws Exception {
        OpenBlasEmbeddedDownloader openBlasEmbeddedDownloader = new OpenBlasEmbeddedDownloader(os,architecture,javaCppOpenBlasVersion,forceDownload);
        openBlasEmbeddedDownloader.download();
        openBlasEmbeddedDownloader.openBlasDirectory();
        File openblasHome = openBlasEmbeddedDownloader.openBlasHome();
        if(!openblasHome.exists()) {
            System.err.println("Openblas download did not succeed. Exiting.");
            return 1;
        }

        EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"OPENBLAS_HOME",openblasHome.getAbsolutePath());
        EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"OPENBLAS_PATH",openblasHome.getAbsolutePath());

        if(architecture.contains("arm") && os.equals("linux")) {
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"BUILD_USING_MAVEN","1");
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"TARGET_OS",os);
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"CURRENT_TARGET",os + "-" + architecture);
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"LIBND4J_CLASSIFIER","linux-" + architecture);
        } else if(os.equals("android")) {
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"BUILD_USING_MAVEN","1");
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"TARGET_OS",os);
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"CURRENT_TARGET",os + "-" + architecture);
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"LIBND4J_CLASSIFIER","android-" + architecture);
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"ANDROID_NDK_HOME", Info.homeDirectory() + "/android-ndk-r21d");
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"CROSS_COMPILER_DIR",Info.homeDirectory() + "/android-ndk-r21d");
            EnvironmentFile.writeEnvForClassifierAndBackend("nd4j-native",os + "-" + architecture,"NDK_VERSION","r21d");

        }

        System.out.println(openBlasEmbeddedDownloader.openBlasDirectory());
        return 0;
    }
}
