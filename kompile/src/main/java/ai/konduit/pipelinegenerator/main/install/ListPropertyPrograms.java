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
