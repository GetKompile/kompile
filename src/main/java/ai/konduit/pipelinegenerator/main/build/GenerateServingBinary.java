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

package ai.konduit.pipelinegenerator.main.build;

import ai.konduit.pipelinegenerator.main.Info;
import ai.konduit.serving.data.image.step.ndarray.ImageToNDArrayStep;
import ai.konduit.serving.models.deeplearning4j.step.DL4JStep;
import ai.konduit.serving.models.onnx.step.ONNXStep;
import ai.konduit.serving.models.samediff.step.SameDiffStep;
import ai.konduit.serving.models.tensorflow.step.TensorFlowStep;
import ai.konduit.serving.models.tvm.step.TVMStep;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.python.PythonStep;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.nd4j.common.io.ClassPathResource;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-serving-binary",
        mixinStandardHelpOptions = false,
        description = "Generate a binary meant for serving models. This will be a static linked binary meant for execution of konduit serving pipelines." +
                " This command may require additional tools such as graalvm, maven and a local compiler such as gcc to run correctly.")
public class GenerateServingBinary extends BaseGenerateImageAndSdk {

    @CommandLine.Option(names = {"--protocol"},description = "The protocol to use with serving",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String protocol;


    public GenerateServingBinary() {
    }


    @Override
    public void setCustomDefaults() {
        //build static shared lib that serves models
        server = true;
        buildSharedLibrary = false;
    }

    @Override
    public void doCustomCommands(List<String> commands) {

        addCommand(protocol,"--protocol",commands);
    }
}
