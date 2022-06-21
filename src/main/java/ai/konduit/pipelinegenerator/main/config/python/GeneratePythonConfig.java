package ai.konduit.pipelinegenerator.main.config.python;

import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.model.PythonIO;
import ai.konduit.serving.pipeline.api.data.ValueType;
import ai.konduit.serving.pipeline.api.python.models.PythonConfigType;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "generate-python-config",mixinStandardHelpOptions = false)
public class GeneratePythonConfig implements Callable<Integer> {

    @CommandLine.Option(names = {"--pythonPath"},description = "The python path to use",required = false)
    private String pythonPath;
    @CommandLine.Option(names = {"--pythonConfigType"},description = "The python path to use",required = false)
    private String pythonConfigType;
    @CommandLine.Option(names = {"--pythonCode"},description = "The python path to use",required = false)
    private String pythonCode;
    @CommandLine.Option(names = {"--pythonCodePath"},description = "The python path to use",required = false)
    private String pythonCodePath;
    @CommandLine.Option(names = {"--returnAllInputs"},description = "The python path to use",required = false)
    private boolean returnAllInputs;
    @CommandLine.Option(names = {"--setupAndRun"},description = "The python path to use",required = false)
    private boolean setupAndRun;
    @CommandLine.Option(names = {"--pythonLibrariesPath"},description = "The python path to use",required = false)
    private String pythonLibrariesPath;
    @CommandLine.Option(names = {"--inputVariable"},description = "The input variables to use",required = false)
    private List<String> inputVariables;

    @CommandLine.Option(names = {"--outputVariable"},description = "The output variables to return from the result",required = false)
    private List<String> outputVariables;

    public GeneratePythonConfig() {
    }


    @Override
    public Integer call() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        System.out.println("ValueType values length is " + ValueType.values().length);
        List<ValueType> types = Arrays.asList(ValueType.values());
        List<String> names = types.stream().map(input -> input.name()).collect(Collectors.toList());
        System.out.println("Names to convert " + names);

        PythonConfig.PythonConfigBuilder pythonConfig = PythonConfig.builder();

        if(pythonCode != null) {
            pythonConfig.pythonCode(pythonCode);
        }
        if(pythonCodePath != null) {
            pythonConfig.pythonCodePath(pythonCodePath);

        }
        if(pythonConfigType != null) {
            pythonConfig.pythonConfigType(PythonConfigType.valueOf(pythonConfigType.toUpperCase(Locale.ROOT)));

        }


        if(pythonPath != null) {
            pythonConfig.pythonPath(pythonPath);
        }

        if(pythonLibrariesPath != null) {
            pythonConfig.pythonLibrariesPath(pythonLibrariesPath);
        }

        pythonConfig.returnAllInputs(returnAllInputs);
        pythonConfig.setupAndRun(setupAndRun);

        if(inputVariables != null) {
            for(int i = 0; i < inputVariables.size(); i++) {
                String input = inputVariables.get(i);
                String json = FileUtils.readFileToString(new File(input));
                PythonIO pythonIO = ObjectMappers.fromJson(json,PythonIO.class);
                pythonConfig.ioInput(pythonIO.name(),pythonIO);
            }
        }


        if(outputVariables != null) {
            for(int i = 0; i < outputVariables.size(); i++) {
                String output = outputVariables.get(i);
                String json = FileUtils.readFileToString(new File(output));
                PythonIO pythonIO = ObjectMappers.fromJson(json,PythonIO.class);
                pythonConfig.ioOutput(pythonIO.name(),pythonIO);
            }
        }

        System.out.println(pythonConfig.build().toJson());

        return 0;
    }


}
