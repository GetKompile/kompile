/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.image;


import picocli.CommandLine;

import java.util.Arrays;

/**
 * Converts a String representation into an {@link ImageNormalization} configuration object.
 *
 * The expected string format is space-separated values:
 * "TYPE [meanR meanG meanB [stdR stdG stdB [maxValue]]]"
 * Example: "SUBTRACT_MEAN 123.68 116.779 103.939"
 * Example: "STANDARDIZE 0.485 0.456 0.406 0.229 0.224 0.225 255.0"
 * Example: "SCALE 255.0"
 * Example: "INCEPTION"
 * Example: "NONE"
 *
 * - TYPE: The normalization type (e.g., NONE, SCALE, SUBTRACT_MEAN, STANDARDIZE). Case-insensitive.
 * - meanR, meanG, meanB: Required if TYPE is SUBTRACT_MEAN or STANDARDIZE.
 * - stdR, stdG, stdB: Required if TYPE is STANDARDIZE.
 * - maxValue: Optional final value. Used by SCALE, SCALE_01, INCEPTION.
 */
public class ImageNormalizationTypeConverter implements CommandLine.ITypeConverter<ImageNormalization> {

    @Override
    public ImageNormalization convert(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            throw new CommandLine.TypeConversionException("Input string for ImageNormalization cannot be null or empty.");
        }

        String[] split = value.trim().split("\\s+"); // Split by one or more whitespace characters
        if (split.length < 1) {
            throw new CommandLine.TypeConversionException("Input string must contain at least the normalization type.");
        }

        ImageNormalization.Type type;
        try {
            type = ImageNormalization.Type.valueOf(split[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException("Invalid ImageNormalization type '" + split[0] +
                    "'. Valid types are: " + Arrays.toString(ImageNormalization.Type.values()));
        }

        double[] mean = null;
        double[] std = null;
        Double maxValue = null;
        int currentIdx = 1; // Start parsing values after the type

        try {
            // Parse mean if applicable and available
            if (type == ImageNormalization.Type.SUBTRACT_MEAN || type == ImageNormalization.Type.STANDARDIZE) {
                if (split.length >= currentIdx + 3) {
                    mean = new double[3];
                    mean[0] = Double.parseDouble(split[currentIdx++]);
                    mean[1] = Double.parseDouble(split[currentIdx++]);
                    mean[2] = Double.parseDouble(split[currentIdx++]);
                } else {
                    // VGG_SUBTRACT_MEAN and IMAGE_NET don't require explicit mean/std
                    if (type != ImageNormalization.Type.VGG_SUBTRACT_MEAN && type != ImageNormalization.Type.IMAGE_NET) {
                        throw new CommandLine.TypeConversionException("Type " + type + " requires 3 mean values (R G B) unless it's VGG_SUBTRACT_MEAN or IMAGE_NET.");
                    }
                }
            }

            // Parse std dev if applicable and available
            if (type == ImageNormalization.Type.STANDARDIZE) {
                if (split.length >= currentIdx + 3) {
                    std = new double[3];
                    std[0] = Double.parseDouble(split[currentIdx++]);
                    std[1] = Double.parseDouble(split[currentIdx++]);
                    std[2] = Double.parseDouble(split[currentIdx++]);
                } else {
                    // IMAGE_NET doesn't require explicit std
                    if(type != ImageNormalization.Type.IMAGE_NET) {
                        throw new CommandLine.TypeConversionException("Type " + type + " requires 3 standard deviation values (R G B) unless it's IMAGE_NET.");
                    }
                }
            }

            // Parse optional maxValue if available as the next token
            // Relevant for SCALE, SCALE_01, INCEPTION
            if (split.length > currentIdx) {
                // Check if the current type actually USES maxValue before parsing as such
                // Or just parse if available and let the ImageNormalization logic handle it?
                // Let's parse if available.
                maxValue = Double.parseDouble(split[currentIdx++]);
            }

            // Check for extraneous arguments
            if (split.length > currentIdx) {
                System.err.println("Warning: Extraneous values found in ImageNormalization string after index " + (currentIdx - 1) + ": " +
                        String.join(" ", Arrays.asList(split).subList(currentIdx, split.length)));
            }

        } catch (NumberFormatException e) {
            throw new CommandLine.TypeConversionException("Invalid number format encountered while parsing ImageNormalization string: " + e.getMessage());
        } catch (IndexOutOfBoundsException e) {
            // This shouldn't happen with the length checks, but as a safeguard
            throw new CommandLine.TypeConversionException("Insufficient values provided for ImageNormalization type " + type);
        }


        // Use the builder from the ported Kompile ImageNormalization class
        ImageNormalization.ImageNormalizationBuilder builder = ImageNormalization.builder();
        builder.type(type);

        if (mean != null) {
            builder.meanRgb(mean); // Use field name from ported class
        }
        if (std != null) {
            builder.stdRgb(std); // Use field name from ported class
        }
        if (maxValue != null) {
            builder.maxValue(maxValue);
        }

        return builder.build();
    }
}