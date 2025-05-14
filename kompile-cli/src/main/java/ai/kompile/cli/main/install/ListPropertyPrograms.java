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

import org.nd4j.common.io.ClassPathResource;
import picocli.CommandLine;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "install-tool-list",description = "Allows installation of tools by resolving install commands from command line from JVM properties or pre specified properties files that match the platform.")
public class ListPropertyPrograms implements Callable<Integer> {
    @CommandLine.Option(names = {"--platformName"},description = "The platform to list installable programs for")
    private String platformName;



    @Override
    public Integer call() throws Exception {
        ClassPathResource classPathResource = new ClassPathResource("programs." + platformName + ".properties");
        if(!classPathResource.exists()) {
            System.err.println("Unable to list programs for platform " + platformName);
            return 1;
        }
        try(InputStream is = classPathResource.getInputStream()) {
            Properties properties = new Properties();
            properties.load(is);
            if(properties.contains(platformName + ".programs")) {
                System.out.println("Available programs are: " + properties.getProperty(platformName + ".programs"));
            }
        }

        return 0;
    }


}
