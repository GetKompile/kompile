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

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.util.OSResolver;
import ai.kompile.cli.main.util.BackendInfo;
import ai.kompile.cli.main.util.EnvironmentFile;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "refresh-index",mixinStandardHelpOptions = false)
public class RefreshIndexPrograms implements Callable<Integer> {
    @CommandLine.Option(names = {"--baseUrl"},description = "base URL for the index",required = false)
    private String baseUrl = "https://raw.githubusercontent.com/KonduitAI/kompile-program-repository/main/";

    public RefreshIndexPrograms() {
    }

    @Override
    public Integer call() throws Exception {
        String os = OSResolver.os();
        String url = ProgramIndex.indexForPlatform(os,baseUrl);

        File programIndex = new File(Info.homeDirectory(),"program-index");
        if(programIndex.exists()) {
            FileUtils.deleteDirectory(programIndex);
        }

        programIndex.mkdirs();

        File backendEnvs = new File(Info.homeDirectory(),EnvironmentFile.BACKEND_ENVS_DIR);
        if(backendEnvs.exists()) {
            FileUtils.deleteDirectory(backendEnvs);
        }

        //delete everything and redownload the index first so we have backend information
        CommandLine commandLine = new CommandLine(new ProgramIndex());
        int exec = commandLine.execute("--updateIndexForce=true");
        if(exec != 0) {
            System.err.println("Failed to download program index");
            return 1;
        }

        File platformIndex = new File(programIndex,os);
        platformIndex.mkdirs();


        File index = new File(platformIndex, ProgramIndex.programsFileForPlatform(os));
        index.getParentFile().mkdirs();

        BackendInfo backendInfo = BackendInfo.backendClassifiersForBackend(OSResolver.os());

        if(!index.exists() || baseUrl == null) {
            InstallMain.downloadTo(url, index.getAbsolutePath(), true);

            String load = FileUtils.readLines(index, Charset.defaultCharset())
                    .stream().filter(input -> input.contains("="))
                    .collect(Collectors.toList()).get(0);

            String[] split = load.split("=");
            String[] programs = split[1].split(",");
            System.out.println("Found programs... " + split[1] + " for platform " + os);
            for (String program : programs) {
                downloadProgram(os, programIndex, program);
                setupBackend(backendInfo,os,program);
            }
        }

        return 0;
    }

    private void setupBackend(BackendInfo backendInfo,String os,String programName) throws IOException {
        String relevantBackends = PropertyBasedInstaller.resolveProperty(programName,  os + ".backends",os);

        if(relevantBackends != null) {
            String[] backends = relevantBackends.split(",");
            System.out.println("Writing environments for backends " + relevantBackends);
            for(String backend : backends) {
                System.out.println("Writing environments for backend " + backend);
                String backendEnvs = PropertyBasedInstaller.resolveProperty(programName,backend + ".envs",os);
                if(backendEnvs != null) {
                    System.out.println("Backend environments found " + backendEnvs);
                    String[] split = backendEnvs.split(",");
                    for(String env : split) {
                        System.out.println("Processing environment " + env);
                        List<String> classifiersForBackend = backendInfo.getClassifiersForBackend(backend);
                        if(classifiersForBackend != null) {
                            System.out.println("Writing environment properties for  for backend " + backend);
                            for (String classifier : classifiersForBackend) {
                                System.out.println("Processing classifier " + classifier);
                                String property = PropertyBasedInstaller.resolveProperty(programName, backend + ".env." + env, os);
                                if (property != null)
                                    EnvironmentFile.writeEnvForClassifierAndBackend(backend, classifier, env, property);
                                else {
                                    System.out.println("No property found for " + classifier + " and backend " + backend);
                                }
                            }
                        }

                    }
                } else {
                    System.out.println("No backend environments found for " + programName + " and backend " + backend);
                }
            }

        }

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
}
