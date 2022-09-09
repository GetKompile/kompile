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

import ai.konduit.pipelinegenerator.main.util.EnvironmentUtils;
import ai.konduit.pipelinegenerator.main.util.OS;
import ai.konduit.pipelinegenerator.main.util.OSResolver;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.Nullable;
import org.nd4j.autodiff.samediff.internal.DependencyTracker;
import org.nd4j.common.io.ClassPathResource;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

@CommandLine.Command(name = "install-tool",description = "Allows installation of tools by resolving install commands from command line from JVM properties or pre specified properties files that match the platform.")
public class PropertyBasedInstaller implements Callable<Integer> {
    @CommandLine.Option(names = {"--programName"},description = "The place to clone deeplearning4j for a build: defaults to $USER/.kompile/deeplearning4j")
    private String programName;


    private void getDepAllDeps(String program, DependencyTracker<String,String> allDeps) throws IOException {
        String dependencies = resolveProperty(program, "dependencies", OSResolver.os());
        if (dependencies != null) {
            String[] deps = dependencies.split(",");
            if (deps.length > 0) {
                System.out.println("Dependencies found for program name " + programName + " : " + dependencies + ". Ensuring installed.");
                for (String dep : deps) {
                    if (dep.isEmpty()) {
                        continue;
                    }

                    allDeps.addDependency(dep,program);
                    getDepAllDeps(dep,allDeps);
                }
            } else {
                System.out.println("No dependencies found for " + programName + ". Installing.");
            }
        }
    }
    @Override
    public Integer call() throws Exception {
        //run this before even resolving properties just in case it's already installed.
        File file = EnvironmentUtils.executableOnPath(programName);
        if(file != null && file.exists()) {
            System.out.println("Program name " + file.getName() + " already installed. Exiting.");
            return 0;
        }

        DependencyTracker<String,String> dependencyTracker = new DependencyTracker<>();
        getDepAllDeps(programName,dependencyTracker);
        List<String> reverseOrder = new ArrayList<>();
        int exit = 0;
        dependencyTracker.markSatisfied(programName,true);
        Set<String> ran = new HashSet<>();
        while(dependencyTracker.hasNewAllSatisfied()) {
            String curr = dependencyTracker.getNewAllSatisfied();
            reverseOrder.add(curr);
            dependencyTracker.markSatisfied(curr,true);
        }

        Collections.reverse(reverseOrder);
        //append to end if not already added
        if(!reverseOrder.contains(programName))
            reverseOrder.add(programName);
        for(String curr : reverseOrder) {
            if(!ran.contains(curr)) {
                exit = install(curr);
                ran.add(curr);
            }
        }

        return exit;
    }

    private int install(String programName) throws IOException, InterruptedException, ExecutionException {
        File file = EnvironmentUtils.executableOnPath(programName);
        if(file != null && file.exists()) {
            System.out.println("Program name " + file.getName() + " already installed. Exiting.");
            return 0;
        }
        String os = OSResolver.os();
        String commandValue = resolveProperty(programName,"installCommand" ,os);
        if (commandValue == null) {
            System.err.println("Unable to resolve install command for program " + programName);
            return 1;
        }

        System.out.println("Running " + commandValue);
        File tempFileWrite = new File(UUID.randomUUID() + ".sh");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("#!/bin/bash\n");
        stringBuilder.append(commandValue + "\n");
        tempFileWrite.deleteOnExit();
        tempFileWrite.setExecutable(true);
        FileUtils.write(tempFileWrite,stringBuilder.toString(), Charset.defaultCharset());
        ProcessResult processResult = new ProcessExecutor()
                .command("bash",tempFileWrite.getAbsolutePath())
                .readOutput(true)
                .redirectOutput(System.out)
                .start().getFuture().get();
        if(processResult.hasOutput())
            System.out.println("Command output was \n " + processResult.outputUTF8());
        return processResult.getExitValue();
    }

    @Nullable
    private String resolveProperty(String programName, String propertyToResolve, String os) throws IOException {
        if(programName == null || programName.isEmpty()) {
            return null;
        }
        StringBuilder commandProperty = new StringBuilder();
        commandProperty.append(programName);
        commandProperty.append(".");
        commandProperty.append(propertyToResolve);
        String property = commandProperty.toString();
        String commandValue = null;
        //not set on command line try to
        if(!System.getProperties().containsKey(property)) {
            StringBuilder resourceName = new StringBuilder();
            resourceName.append(programName);
            resourceName.append(".");
            resourceName.append("dependency");
            resourceName.append(".");
            resourceName.append(os);
            resourceName.append(".");
            resourceName.append("properties");
            ClassPathResource classPathResource = new ClassPathResource(resourceName.toString());
            if(!classPathResource.exists()) {
                if(OS.OS.isUnix()) {
                    System.out.println("Resource " + resourceName + " does not exist. Trying generic-linux fallback.");
                    String originalResourceName = resourceName.toString();
                    resourceName = new StringBuilder();
                    resourceName.append(programName);
                    resourceName.append(".");
                    resourceName.append("dependency");
                    resourceName.append(".");
                    resourceName.append("generic-linux");
                    resourceName.append(".");
                    resourceName.append("properties");
                    classPathResource = new ClassPathResource(resourceName.toString());
                    if(!classPathResource.exists()) {
                        System.err.println("Unable to resolve property " + commandProperty + " for installing program " + programName + " . Tried to resolve property with resources " + originalResourceName + " and " + resourceName +  " as a fallback for generic linux systems lacking install commands.");
                        return null;
                    }
                } else {
                    System.err.println("No fall back supported for non linux systems. Returning null");
                    return null;
                }
            }

            Properties properties = new Properties();
            try(InputStream inputStream = classPathResource.getInputStream()) {
                properties.load(inputStream);
                if(!properties.containsKey(commandProperty.toString())) {
                    System.err.println("Tried to use resource " + resourceName + " for property " + commandProperty + " but no property value was found. Returning null.");
                    return null;
                }

                commandValue = properties.getProperty(commandProperty.toString());

            }
        } else {
            commandValue = System.getProperty(commandProperty.toString());
            System.out.println("Resolved property " + commandProperty + " from command line. Running specified command.");
        }

        //resolve all property value placeholders before returning
        commandValue = EnvironmentUtils.resolveEnvPropertyValue(commandValue);
        return commandValue;
    }
}