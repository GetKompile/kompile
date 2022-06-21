package ai.konduit.pipelinegenerator.main.util;

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "analyze_model",mixinStandardHelpOptions = false)
public class ModelFileDebug implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelType"},
            description = "The type of model to analyze. Possible values are: keras,dl4j,pytorch,onnx,tensorflow",required = true)
    private String fileType;
    @CommandLine.Option(names = {"--filePath"},description = "The model file to analyze.",required = true,usageHelp = true)
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
