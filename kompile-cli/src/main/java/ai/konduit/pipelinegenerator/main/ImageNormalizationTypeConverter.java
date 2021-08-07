package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.data.image.convert.config.ImageNormalization;
import picocli.CommandLine;

import java.util.Arrays;

/**
 * Split the image normalization configuration by space
 * due to us using CSV for parsing other values.
 */
public class ImageNormalizationTypeConverter implements CommandLine.ITypeConverter<ImageNormalization> {
    @Override
    public ImageNormalization convert(String value) throws Exception {
        String[] split = value.split(" ");
        ImageNormalization.Type type = ImageNormalization.Type.valueOf(split[0].toUpperCase());
        //first 3 values are mean, second 3 values are standard deviation
        double[] mean = null,std = null;
        Double maxValue = null;
        if(split.length >= 4) {
            mean = new double[3];
            mean[0] = Double.parseDouble(split[1]);
            mean[1] = Double.parseDouble(split[2]);
            mean[2] = Double.parseDouble(split[3]);
        }

        if(split.length >= 7) {
            std = new double[3];
            std[0] = Double.parseDouble(split[4]);
            std[1] = Double.parseDouble(split[5]);
            std[2] = Double.parseDouble(split[6]);
        }

        if(split.length >= 8) {
            maxValue = Double.parseDouble(split[7]);
        }

        return new ImageNormalization(type,maxValue,mean,std);
    }
}
