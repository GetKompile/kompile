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

package ai.kompile.cli.main.converter;

// Changed import to use the Kompile Point class
import ai.kompile.pipelines.framework.api.data.Point;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts a String representation into a {@link ai.kompile.pipelines.framework.api.data.Point}.
 *
 * The expected string format is a comma-separated list of key-value pairs,
 * where each pair is in the format "key=value".
 * Example: "dimensions=1.0 2.5 3.0,label=myPoint,probability=0.9"
 *
 * - The "dimensions" key expects space-separated double values for the coordinates.
 * - The "label" key expects the string label.
 * - The "probability" key expects a double value for the probability.
 *
 * All keys are optional, but "dimensions" is typically expected for a valid point.
 */
public class PointConverter implements CommandLine.ITypeConverter<Point> {

    @Override
    public Point convert(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            throw new CommandLine.TypeConversionException("Input string for Point cannot be null or empty.");
        }

        String[] pairs = value.split(",");
        Map<String, String> inputMap = new HashMap<>();
        for (String pair : pairs) {
            String[] keyVal = pair.split("=", 2); // Split only on the first '='
            if (keyVal.length == 2) {
                inputMap.put(keyVal[0].trim(), keyVal[1].trim());
            } else {
                // Handle cases like "label=" (empty value) or invalid format
                if (keyVal.length == 1 && pair.endsWith("=")) {
                    inputMap.put(keyVal[0].trim(), "");
                } else {
                    throw new CommandLine.TypeConversionException("Invalid format in Point string part: '" + pair + "'. Expected format 'key=value'.");
                }
            }
        }

        Point.PointBuilder builder = Point.builder();
        double[] parsedDimensions = null;
        String parsedLabel = null;
        Double parsedProbability = null;

        for (Map.Entry<String, String> entry : inputMap.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();

            try {
                switch (key) {
                    // Use "dimensions" field name from Kompile Point class
                    case "dimensions":
                        if (val.isEmpty()) {
                            parsedDimensions = new double[0];
                        } else {
                            String[] coordSplit = val.split("\\s+"); // Split by whitespace
                            parsedDimensions = new double[coordSplit.length];
                            for (int i = 0; i < coordSplit.length; i++) {
                                parsedDimensions[i] = Double.parseDouble(coordSplit[i]);
                            }
                        }
                        break;
                    case "label":
                        parsedLabel = val;
                        break;
                    case "probability":
                        if (!val.isEmpty()) {
                            parsedProbability = Double.parseDouble(val);
                        }
                        break;
                    default:
                        // Optionally ignore or warn about unknown keys
                        // System.err.println("Warning: Unknown key '" + key + "' in Point string conversion.");
                        break;
                }
            } catch (NumberFormatException e) {
                throw new CommandLine.TypeConversionException("Failed to parse number for key '" + key + "' with value '" + val + "': " + e.getMessage());
            }
        }

        // Build the Point object using the parsed values
        if (parsedDimensions != null) {
            builder.dimensions(parsedDimensions);
        } else {
            // Handle case where dimensions key was missing - maybe throw error or default?
            throw new CommandLine.TypeConversionException("Missing required key 'dimensions' for Point conversion.");
            // Or default to empty point: builder.dimensions(new double[0]);
        }

        if (parsedLabel != null) {
            builder.label(parsedLabel);
        }

        if (parsedProbability != null) {
            builder.probability(parsedProbability);
        }

        return builder.build();
    }
}