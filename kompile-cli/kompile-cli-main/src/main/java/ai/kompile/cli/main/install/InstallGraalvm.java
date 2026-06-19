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

import ai.kompile.cli.common.util.ArchiveUtils;
import ai.kompile.cli.main.Info;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "graalvm",mixinStandardHelpOptions = false)
public class InstallGraalvm implements Callable<Integer> {

    public final static String DOWNLOAD_URL = "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-17.0.11+9/graalvm-community-jdk-17.0.11+9.1_linux-x64_bin.tar.gz";
    public final static String FILE_NAME = "graalvm-community-jdk-17.0.11+9.1_linux-x64_bin.tar.gz";
    public final static String EXTRACTED_DIR_NAME = "graalvm-community-jdk-17.0.11+9.1";

    public InstallGraalvm() {
    }

    @Override
    public Integer call() throws Exception {
        File graalvmInstallDir = Info.graalvmDirectory();
        if(graalvmInstallDir.exists() && graalvmInstallDir.list() != null && graalvmInstallDir.list().length > 0) {
            File javaExec = new File(new File(graalvmInstallDir, "bin"), "java");
            if (javaExec.exists()) {
                System.out.println("GraalVM appears to be already installed at " + graalvmInstallDir.getAbsolutePath() + ". Skipping installation.");
                System.out.println("If there is a problem with your install, please run 'kompile uninstall graalvm' and then try installing again.");
                return 0;
            } else {
                System.out.println("GraalVM installation directory " + graalvmInstallDir.getAbsolutePath() + " exists but seems incomplete. Proceeding with installation.");
            }
        }

        File archive = InstallMain.downloadAndLoadFrom(DOWNLOAD_URL,FILE_NAME,false);
        if(archive == null) {
            System.err.println("Failed to download GraalVM archive from " + DOWNLOAD_URL + " or it might be corrupt. Please check the URL and your network connection.");
            return 1;
        }

        File tempExtractDir = new File(archive.getParentFile(), "graalvm_extract_temp_" + System.currentTimeMillis());
        tempExtractDir.mkdirs();

        try {
            System.out.println("Extracting GraalVM to " + tempExtractDir.getAbsolutePath());
            ArchiveUtils.unzipFileTo(archive.getAbsolutePath(), tempExtractDir.getAbsolutePath(), true);

            File extractedGraalVmDir = new File(tempExtractDir, EXTRACTED_DIR_NAME);
            if (!extractedGraalVmDir.exists() || !extractedGraalVmDir.isDirectory()) {
                File[] filesInTemp = tempExtractDir.listFiles();
                if (filesInTemp != null && filesInTemp.length == 1 && filesInTemp[0].isDirectory()) {
                    extractedGraalVmDir = filesInTemp[0];
                    System.out.println("Adjusted extracted directory to: " + extractedGraalVmDir.getName());
                } else if (new File(tempExtractDir, "bin").isDirectory() && new File(tempExtractDir, "lib").isDirectory()){
                    extractedGraalVmDir = tempExtractDir;
                    System.out.println("GraalVM extracted directly into temp directory.");
                } else {
                    System.err.println("Extracted GraalVM directory '" + EXTRACTED_DIR_NAME + "' not found in " + tempExtractDir.getAbsolutePath());
                    System.err.println("Please verify the EXTRACTED_DIR_NAME constant in InstallGraalvm.java matches the archive structure.");
                    System.err.println("Listing contents of " + tempExtractDir.getAbsolutePath() + ": " + Arrays.toString(tempExtractDir.list()));
                    FileUtils.deleteDirectory(tempExtractDir);
                    return 1;
                }
            }

            if (graalvmInstallDir.exists()) {
                FileUtils.cleanDirectory(graalvmInstallDir);
            } else {
                graalvmInstallDir.mkdirs();
            }

            System.out.println("Copying GraalVM from " + extractedGraalVmDir.getAbsolutePath() + " to " + graalvmInstallDir.getAbsolutePath());
            FileUtils.copyDirectory(extractedGraalVmDir, graalvmInstallDir);

            File guExecutableDir = new File(graalvmInstallDir, "bin");
            File guExecutable = new File(guExecutableDir, "gu");

            if (!guExecutable.exists()) {
                guExecutableDir = new File(graalvmInstallDir, "lib/installer/bin"); // Older GraalVM path
                guExecutable = new File(guExecutableDir, "gu");
            }
            if (!guExecutable.exists()) {
                System.err.println("GraalVM Updater (gu) not found in expected locations. Native image component cannot be installed automatically.");
                System.out.println("Please install 'native-image' manually using the 'gu' tool from your GraalVM installation: " + graalvmInstallDir.getAbsolutePath());
                return 1;
            }

            File executablesBinDir = new File(graalvmInstallDir, "bin");
            if(executablesBinDir.exists() && executablesBinDir.isDirectory()) {
                for (File f : executablesBinDir.listFiles()) {
                    f.setExecutable(true, false);
                }
            }

            System.out.println("Installing native-image component using: " + guExecutable.getAbsolutePath());
            int exitValue = new ProcessExecutor().environment(System.getenv())
                    .command(Arrays.asList(guExecutable.getAbsolutePath(), "install", "native-image"))
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .redirectError(System.err)
                    .execute().getExitValue();

            if (exitValue == 0) {
                System.out.println("Successfully installed native-image component.");
                System.out.println("GraalVM (JDK 17 based) installed at " + graalvmInstallDir.getAbsolutePath());
            } else {
                System.err.println("Failed to install native-image component. Exit code: " + exitValue);
                System.out.println("GraalVM core installed at " + graalvmInstallDir.getAbsolutePath() + ", but native-image installation failed.");
                return exitValue;
            }
            return 0;
        } finally {
            FileUtils.deleteDirectory(tempExtractDir);
            if (archive != null && archive.exists()) {
                archive.delete();
            }
        }
    }
}