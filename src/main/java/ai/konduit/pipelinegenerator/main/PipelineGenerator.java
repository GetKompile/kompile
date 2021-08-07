package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.cli.launcher.command.ConfigCommand;
import ai.konduit.serving.pipeline.api.pipeline.Pipeline;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pipeline-generate",mixinStandardHelpOptions = true)
public class PipelineGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--pipeline"},description = "Pipeline String",required = true)
    private String pipeline;
    @CommandLine.Option(names = {"--output-file"},description = "Output file",required = true)
    private File outputFile;
    @CommandLine.Option(names = {"--output-format"},description = "Output format (json or yml)")
    private String format = "json";

    @Override
    public Void call() throws Exception {
        ConfigCommand configCommand = new ConfigCommand();
        Pipeline pipeline = configCommand.pipelineFromString(this.pipeline);
        if(format.equals("json")) {
            FileUtils.write(outputFile,pipeline.toJson(), Charset.defaultCharset());
        } else if(format.equals("yml")) {
            FileUtils.write(outputFile,pipeline.toYaml(), Charset.defaultCharset());
        }

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


