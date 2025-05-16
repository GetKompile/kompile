/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.exec;


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


