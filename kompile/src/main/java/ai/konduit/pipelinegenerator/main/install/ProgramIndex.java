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
import ai.konduit.pipelinegenerator.main.util.OSResolver;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "program-indexer",mixinStandardHelpOptions = false)
public class ProgramIndex implements Callable<Integer> {
    @CommandLine.Option(names = {"--baseUrl"},description = "base URL for the index",required = false)
    private String baseUrl = "https://raw.githubusercontent.com/KonduitAI/kompile-program-repository/main/";
    @CommandLine.Option(names = {"--programName"},description = "the program name to download",required = false)
    private String programName;
    @CommandLine.Option(names = {"--updateIndexForce"},description = "whether to force update the index",required = false)
    private boolean updateIndexForce = false;



    public static File pathToIndexForPlatform(String platform) {
        File baseIndex = new File(Info.homeDirectory(),"program-index" + File.separator + platform);
        return new File(baseIndex,"programs." + platform + ".properties");
    }

    @Override
    public Integer call() throws Exception {
        String platform = OSResolver.os();
        String url = indexForPlatform(platform,baseUrl);

        File programIndex = new File(Info.homeDirectory(),"program-index");
        if(!programIndex.exists()) {
            programIndex.mkdirs();
        }

        File platformIndex = new File(programIndex,platform);
        if(!platformIndex.exists()) {
            platformIndex.mkdirs();
        }

        File index = new File(platformIndex,programsFileForPlatform(platform));
        if(!index.getParentFile().exists()) {
            index.getParentFile().mkdirs();
        }

        if(!index.exists() || baseUrl == null || updateIndexForce) {
            InstallMain.downloadTo(url, index.getAbsolutePath(), true);

            String load = FileUtils.readLines(index, Charset.defaultCharset())
                    .stream().filter(input -> input.contains("="))
                    .collect(Collectors.toList()).get(0);

            String[] split = load.split("=");
            String[] programs = split[1].split(",");
            System.out.println("Found programs... " + split[1] + " for platform " + platform);
            for (String program : programs) {
                downloadProgram(platform, programIndex, program);
            }
        } else if(programName != null) {
            downloadProgram(platform,programIndex,programName);
        }
        return 0;
    }

    public static String indexForPlatform(String platform,String baseUrl) {
        StringBuilder url = new StringBuilder();
        url.append(baseUrl);
        url.append("/");
        url.append(programsFileForPlatform(platform));
        return url.toString();
    }
    public static String programsFileForPlatform(String platform) {
        return "programs." + platform + ".properties";
    }


    private void downloadProgram(String platform, File programIndex, String program) throws Exception {
        System.out.println("Downloading index for program " + program);
        StringBuilder programFileName = new StringBuilder();
        programFileName.append(program);
        programFileName.append(".dependency." + platform + ".properties");
        StringBuilder fileUrl = new StringBuilder();
        fileUrl.append(baseUrl);
        fileUrl.append(programFileName);
        File platformDir = new File(programIndex,platform);
        if(!platformDir.exists()) {
            platformDir.mkdirs();
        }

        File file = InstallMain.downloadTo(fileUrl.toString(), new File(platformDir, programFileName.toString()).getAbsolutePath(),
                true);
        if(file != null) {
            System.out.println("Downloaded " + file.getAbsolutePath());
        } else {
            System.out.println("Failed to download " + fileUrl + " the file might not exist.");
        }
    }

    /**
     * Returns the program resource file path.
     * This will be the $KOMPILE_PREFIX/program-index/$PLATFORM/$PROGRAM_NAME.dependency.$PLATFORM.properties
     * @param programName the name of the program to look for the local path for
     * @return the file path
     */
    public static File programResource(String programName) {
        String platform = OSResolver.os();
        File programIndex = new File(Info.homeDirectory(),"program-index");
        if(!programIndex.exists()) {
            programIndex.mkdirs();
        }

        File platformIndex = new File(programIndex,platform);
        if(!platformIndex.exists()) {
            platformIndex.mkdirs();
        }

        StringBuilder resourceName = new StringBuilder();
        resourceName.append(programName);
        resourceName.append(".");
        resourceName.append("dependency");
        resourceName.append(".");
        resourceName.append(platform);
        resourceName.append(".");
        resourceName.append("properties");

        File index = new File(platformIndex,resourceName.toString());
        return index;
    }

}
