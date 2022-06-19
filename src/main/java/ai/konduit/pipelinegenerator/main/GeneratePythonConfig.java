package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.pipeline.api.data.ValueType;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.List;
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
        for(int i = 0; i < 13; i++) {
            System.out.println("Value type " + ValueType.values()[i].name());
        }
        List<String> names = types.stream().map(input -> input.name()).collect(Collectors.toList());
        System.out.println("Names to convert " + names);

        if(pythonCode != null) {
            stringBuilder.append("pythonCode=" + pythonCode);
            stringBuilder.append(",");
        }
        if(pythonCodePath != null) {
            stringBuilder.append("pythonCodePath=" + pythonCodePath);
            stringBuilder.append(",");

        }
        if(pythonConfigType != null) {
            stringBuilder.append("pythonConfigType=" + pythonConfigType);
            stringBuilder.append(",");

        }

        if(pythonPath != null) {
            stringBuilder.append("pythonPath=" + pythonPath);
            stringBuilder.append(",");

        }

        if(pythonLibrariesPath != null) {
            stringBuilder.append("pythonLibrariesPath=" + pythonLibrariesPath);
            stringBuilder.append(",");
        }

        stringBuilder.append("returnAllInputs=" + returnAllInputs);
        stringBuilder.append(",");
        stringBuilder.append("setupAndRun=" + setupAndRun);
        //no variables defined, skip comma append
        if(inputVariables != null && !inputVariables.isEmpty() || outputVariables != null && !outputVariables.isEmpty())
            stringBuilder.append(",");

        if(inputVariables != null) {
            for(int i = 0; i < inputVariables.size(); i++) {
                String input = inputVariables.get(i);
                stringBuilder.append("ioInput=" + input);
                //no trailing commas
                if(i < inputVariables.size() - 1)
                    stringBuilder.append(",");
            }

            //append extra comma at the end if output variable is not empty to separate
            if(outputVariables != null && !outputVariables.isEmpty()) {
                stringBuilder.append(",");
            }
        }


        if(outputVariables != null) {
            for(int i = 0; i < outputVariables.size(); i++) {
                String output = outputVariables.get(i);
                stringBuilder.append("ioOutput=" + output);
                //no trailing commas
                if(i < outputVariables.size() - 1)
                    stringBuilder.append(",");
            }
        }

        System.out.println(stringBuilder);
        /*
        *  String[] split = value.split(",");
        PythonConfig.PythonConfigBuilder builder = PythonConfig.builder();
        for(String keyVals : split) {
            String[] keyVal = keyVals.split("=");
            switch(keyVal[0]) {
                case "pythonPath":
                    builder.pythonPath(keyVal[1]);
                    break;
                case "pythonConfigType":
                    builder.pythonConfigType(PythonConfigType.valueOf(keyVal[1].toUpperCase()));
                    break;
                case  "pythonCode":
                    builder.pythonCode(keyVal[1]);
                    break;
                case "pythonCodePath":
                    builder.pythonCodePath(keyVal[1]);
                    break;
                case "returnAllInputs":
                    builder.returnAllInputs(Boolean.parseBoolean(keyVal[1]));
                    break;
                case "setupAndRun":
                    builder.setupAndRun(Boolean.parseBoolean(keyVal[1]));
                    break;
                case "pythonLibrariesPath":
                    builder.pythonLibrariesPath(keyVal[1]);
                    break;
                case "ioInput":
                    PythonIO.PythonIOBuilder pythonIOBuilder = PythonIO.builder();
                    String[] ioDescriptor = keyVal[1].split(" ");
                    pythonIOBuilder.name(ioDescriptor[0]);
                    if(ioDescriptor.length > 1)
                        pythonIOBuilder.pythonType(ioDescriptor[1]);
                    if(ioDescriptor.length > 2)
                        pythonIOBuilder.type(ValueType.valueOf(ioDescriptor[2]));
                    if(ioDescriptor.length > 3)
                        pythonIOBuilder.secondaryType(ValueType.valueOf(ioDescriptor[3]));
                    builder.ioInput(ioDescriptor[0],pythonIOBuilder
                            .build());
                    break;
                case "ioOutput":
                    PythonIO.PythonIOBuilder pythonIOBuilderOut = PythonIO.builder();
                    String[] ioDescriptorOut = keyVal[1].split(" ");
                    pythonIOBuilderOut.name(ioDescriptorOut[0]);
                    if(ioDescriptorOut.length > 1)
                        pythonIOBuilderOut.pythonType(ioDescriptorOut[1]);
                    if(ioDescriptorOut.length > 2)
                        pythonIOBuilderOut.type(ValueType.valueOf(ioDescriptorOut[2]));
                    if(ioDescriptorOut.length > 3)
                        pythonIOBuilderOut.secondaryType(ValueType.valueOf(ioDescriptorOut[3]));
                    builder.ioOutput(ioDescriptorOut[0],pythonIOBuilderOut.build());
                    break;
            }
        }

        return builder.build(); */
        return 0;
    }


}
