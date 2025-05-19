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
import ai.kompile.cli.main.util.EnvironmentFile;
import ai.kompile.cli.main.util.OpenBlasEmbeddedDownloader;
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

    // Note: This installs OpenBLAS 0.3.19 linked against JavaCPP preset 1.5.7.
    // The main Kompile project (kompile/pom.xml) uses JavaCPP version 1.5.11 (as of parent POM).
    // For applications built via KompileApplicationBuilder, ND4J's own native dependencies (including OpenBLAS)
    // should be resolved correctly via Maven for the target platform and the project's JavaCPP version.
    // This installer might be for specific offline scenarios or for users wanting to force this particular OpenBLAS version.
    // Ensure this version is compatible if overriding Maven's transitive dependencies for ND4J.
    @CommandLine.Option(names = {"--javaCppOpenBlasVersion"},
            description = "The openblas version to use (format: openblasVersion-javaCppPresetVersion). Default: 0.3.19-1.5.7",required = false)
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