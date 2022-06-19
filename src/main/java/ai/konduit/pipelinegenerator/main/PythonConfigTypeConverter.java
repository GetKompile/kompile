package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.model.PythonIO;
import ai.konduit.serving.pipeline.api.data.ValueType;
import ai.konduit.serving.pipeline.api.python.models.PythonConfigType;
import org.apache.commons.io.FileUtils;
import org.nd4j.common.base.Preconditions;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class PythonConfigTypeConverter implements CommandLine.ITypeConverter<PythonConfig> {
    @Override
    public PythonConfig convert(String value) throws Exception {
        String[] split = value.split(",");
        PythonConfig.PythonConfigBuilder builder = PythonConfig.builder();
        List<ValueType> types = Arrays.asList(ValueType.values());
        List<String> names = types.stream().map(input -> input.name()).collect(Collectors.toList());

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
                    File inputFile = new File(keyVal[1]);
                    String content = FileUtils.readFileToString(inputFile);
                    String[] ioDescriptor = content.split(" ");
                    pythonIOBuilder.name(ioDescriptor[0]);
                    if(ioDescriptor.length > 1)
                        pythonIOBuilder.pythonType(ioDescriptor[1]);
                    if(ioDescriptor.length > 2) {
                        Preconditions.checkState(names.indexOf(ioDescriptor[2].trim()) >= 0,"ioDescriptor  did not find item " + ioDescriptor[2]);
                        pythonIOBuilder.type(types.get(names.indexOf(ioDescriptor[2].trim())));
                    }
                    if(ioDescriptor.length > 3) {
                        Preconditions.checkState(names.indexOf(ioDescriptor[3].trim()) >= 0,"ioDescriptor  did not find item " + ioDescriptor[3]);
                        pythonIOBuilder.secondaryType(types.get(names.indexOf(ioDescriptor[3].trim())));
                    }
                    builder.ioInput(ioDescriptor[0],pythonIOBuilder
                            .build());
                    break;
                case "ioOutput":
                    PythonIO.PythonIOBuilder pythonIOBuilderOut = PythonIO.builder();
                    File outputFile = new File(keyVal[1]);
                    String content2 = FileUtils.readFileToString(outputFile);
                    String[] ioDescriptorOut = content2.split(" ");
                    pythonIOBuilderOut.name(ioDescriptorOut[0]);
                    if(ioDescriptorOut.length > 1)
                        pythonIOBuilderOut.pythonType(ioDescriptorOut[1]);
                    if(ioDescriptorOut.length > 2) {
                        Preconditions.checkState(names.indexOf(ioDescriptorOut[2].trim()) >= 0,"ioDescriptor out did not find item " + ioDescriptorOut[2]);
                        pythonIOBuilderOut.type(types.get(names.indexOf(ioDescriptorOut[2].trim())));
                    }
                    if(ioDescriptorOut.length > 3) {
                        Preconditions.checkState(names.indexOf(ioDescriptorOut[3].trim()) >= 0,"ioDescriptor out did not find item " + ioDescriptorOut[3]);
                        pythonIOBuilderOut.secondaryType(types.get(names.indexOf(ioDescriptorOut[3].trim())));
                    }
                    builder.ioOutput(ioDescriptorOut[0],pythonIOBuilderOut.build());
                    break;
            }
        }

        return builder.build();
    }
}
