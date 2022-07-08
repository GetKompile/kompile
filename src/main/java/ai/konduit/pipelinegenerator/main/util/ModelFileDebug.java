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

package ai.konduit.pipelinegenerator.main.util;

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "analyze_model",mixinStandardHelpOptions = false)
public class ModelFileDebug implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelType"},
            description = "The type of model to analyze. Possible values are: keras,dl4j,pytorch,onnx,tensorflow",required = true)
    private String fileType;
    @CommandLine.Option(names = {"--filePath"},description = "The model file to analyze.",required = true)
    private String filePath;

    public enum FrameworkType {
        KERAS,
        DL4J,
        PYTORCH,
        ONNX,
        TENSORFLOW
    }


    @Override
    public Integer call() throws Exception {
        switch(FrameworkType.valueOf(fileType.toUpperCase())) {
            case TENSORFLOW:

                break;
            case KERAS:
                break;
            case ONNX:
                break;
            case DL4J:
                break;
            case PYTORCH:
                break;
        }

        return null;
    }
}
