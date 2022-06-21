package ai.konduit.pipelinegenerator.main.converter;

import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.model.PythonIO;
import ai.konduit.serving.pipeline.api.data.ValueType;
import ai.konduit.serving.pipeline.api.python.models.PythonConfigType;
import ai.konduit.serving.pipeline.util.ObjectMappers;
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
        File input = new File(value);
        if(!input.exists()) {
            throw new IllegalStateException("Path to python configuration " + input.getAbsolutePath() + " did not exist! Please specify a path to a json file containing the configuration.");
        }
        String jsonContent = FileUtils.readFileToString(input);
        return  ObjectMappers.fromJson(jsonContent,PythonConfig.class);
    }
}
