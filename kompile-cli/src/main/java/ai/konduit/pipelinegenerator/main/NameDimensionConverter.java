package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.tensorrt.NamedDimension;
import ai.konduit.serving.tensorrt.NamedDimensionList;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

public class NameDimensionConverter implements CommandLine.ITypeConverter<NamedDimensionList> {
    public final static String ENTRY_DELIMITER = ";";

    @Override
    public NamedDimensionList convert(String value) throws Exception {
        String[] split = value.split(ENTRY_DELIMITER);
        NamedDimensionList namedDimensions = new NamedDimensionList();
        for(String entry : split) {
            NamedDimension.NamedDimensionBuilder builder = NamedDimension.builder();
            String[] entrySplit = entry.split("=");
            String key = entrySplit[0];
            String[] valSplit = entrySplit[1].split(",");
            long[] result = new long[valSplit.length];
            for(int i = 0; i < result.length; i++) {
                result[i] = Long.parseLong(valSplit[i]);
            }

            builder.name(key);
            builder.dimensions(result);
            namedDimensions.add(builder.build());

            builder.build();
        }
        return namedDimensions;
    }
}
