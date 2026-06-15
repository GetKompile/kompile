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

package ai.konduit.pipelinegenerator.main.exec;


import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pipeline-generate",mixinStandardHelpOptions = false)
public class PipelineGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--pipeline"},description = "Pipeline String",required = true)
    private String pipeline;
    @CommandLine.Option(names = {"--output-file"},description = "Output file",required = true)
    private File outputFile;
    @CommandLine.Option(names = {"--output-format"},description = "Output format (json or yml)")
    private String format = "json";

    @Override
    public Void call() throws Exception {
      /*  ConfigCommand configCommand = new ConfigCommand();
        Pipeline pipeline = configCommand.pipelineFromString(this.pipeline);
        if(format.equals("json")) {
            FileUtils.write(outputFile,pipeline.toJson(), Charset.defaultCharset());
        } else if(format.equals("yml")) {
            FileUtils.write(outputFile,pipeline.toYaml(), Charset.defaultCharset());
        }
*/
        return null;
    }


    public static void main(String...args) {
        new CommandLine(new PipelineGenerator()).execute(args);
    }

}


    //python

    //tensorflow

    //onnx

    //dl4j

    //samediff


    //pipeline type (graph/sequence)

    //pre processing if any

    //protocol

    //port


