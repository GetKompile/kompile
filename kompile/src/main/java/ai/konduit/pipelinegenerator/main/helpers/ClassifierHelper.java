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

package ai.konduit.pipelinegenerator.main.helpers;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "classifier-helper", mixinStandardHelpOptions = false)
public class ClassifierHelper implements Callable<Integer> {
    @CommandLine.Option(names = {"--os"},description = "OS to generate classifier for",required = true)
    private String os;
    @CommandLine.Option(names = {"--architecture"},description = "Architecture to generate classifier for",required = false)
    private String architecture;
    @CommandLine.Option(names = {"--extension"},description = "Extension to generate classifier for",required = false)
    private String extension;

    @CommandLine.Option(names = {"--helper"},description = "Helper to generate classifier for",required = false)
    private String helper;

    public ClassifierHelper() {
    }

    @Override
    public Integer call() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        if(os.endsWith("-"))
            os = os.substring(0,os.length() - 2);
        stringBuilder.append(os);
        if(architecture.endsWith("-"))
            architecture = architecture.substring(0,architecture.length() - 2);
        stringBuilder.append("-");
        stringBuilder.append(architecture);
        if(helper != null && !helper.isEmpty()) {
            if(helper.endsWith("-"))
                helper = helper.substring(0,helper.length() - 2);
            if(helper.startsWith("-"))
                helper = helper.substring(1);
            stringBuilder.append("-");
            stringBuilder.append(helper);

        }


        if(extension != null && !extension.isEmpty()) {
            if(extension.endsWith("-"))
                extension = extension.substring(0,extension.length() - 2);
            if(extension.startsWith("-"))
                extension = extension.substring(1);
            stringBuilder.append("-");
            stringBuilder.append(extension);

        }

        System.out.println(stringBuilder);


        return 0;
    }
}
