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

package ai.kompile.pipelines.steps.deeplearning4j.cli.config.generator; // New package

import ai.kompile.pipelines.steps.deeplearning4j.cli.converter.IUpdaterTypeConverter;   // Example of moved converter
import ai.kompile.pipelines.steps.deeplearning4j.cli.converter.IScheduleTypeConverter;   // Example of moved converter

// Import other necessary DL4J classes
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.weights.WeightInit;

import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.learning.config.IUpdater;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.schedule.ISchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

// The command name should be descriptive when listed under 'kompile config'
@Command(name = "dl4j-training",
        description = "Generates a Deeplearning4J NeuralNetConfiguration or ComputationGraphConfiguration JSON/YAML file.",
        mixinStandardHelpOptions = true)
public class TrainingConfigGenerator implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(TrainingConfigGenerator.class);

    @Option(names = {"-o", "--output"}, description = "Output file path for the JSON/YAML configuration.", required = true)
    private File outputFile;

    @Option(names = {"--format"}, defaultValue = "json", description = "Output format: json or yaml. Default: ${DEFAULT-VALUE}")
    private String format = "json";

    @Option(names = {"--graph"}, description = "Generate a ComputationGraphConfiguration (default is NeuralNetConfiguration - MultiLayerNetwork).")
    private boolean isGraph = false;

    // Common NeuralNetConfiguration options
    @Option(names = {"--seed"}, description = "Seed for reproducibility.")
    private Long seed;

    @Option(names = {"--updater"}, description = "Updater configuration (e.g., Adam, Sgd, Nesterovs). Provide as JSON/YAML string or use specific options.",
            converter = IUpdaterTypeConverter.class) // Assumes this converter is moved and adapted
    private IUpdater updater;

    @Option(names = {"--learningRate"}, description = "Global learning rate (used if updater is not fully specified with its own LR).")
    private Double learningRate; // Often part of ISchedule or IUpdater

    @Option(names = {"--lrSchedule"}, description = "Learning rate schedule configuration. Provide as JSON/YAML string.",
            converter = IScheduleTypeConverter.class) // Assumes this converter is moved and adapted
    private ISchedule learningRateSchedule;

    @Option(names = {"--weightInit"}, description = "Default weight initialization (e.g., XAVIER, NORMAL).")
    private WeightInit weightInit;

    @Option(names = {"--biasInit"}, description = "Default bias initialization value.")
    private Double biasInit;

    // Example for a simple layer structure (very simplified for this example)
    // A real TrainingConfigGenerator would need a much more complex way to define layers
    @Option(names = {"--numLayers"}, description = "Number of simple dense layers (for example purpose).")
    private int numLayers = 1;

    @Option(names = {"--numHidden"}, description = "Number of hidden units in dense layers (for example purpose).")
    private int numHidden = 100;

    @Option(names = {"--numOutputs"}, description = "Number of output units (for example purpose).", defaultValue = "10")
    private int numOutputs = 10;

    @Option(names = {"--inputTypeChannels"}, description = "Input type: number of channels (e.g., for CNN).")
    private Long inputTypeChannels;
    @Option(names = {"--inputTypeHeight"}, description = "Input type: height (e.g., for CNN).")
    private Long inputTypeHeight;
    @Option(names = {"--inputTypeWidth"}, description = "Input type: width (e.g., for CNN).")
    private Long inputTypeWidth;
    @Option(names = {"--inputTypeSize"}, description = "Input type: size (e.g., for FeedForward).")
    private Long inputTypeSize;



    private ObjectMapper objectMapper;

    @Override
    public Integer call() throws Exception {
        if ("yaml".equalsIgnoreCase(format)) {
            objectMapper = new ObjectMapper(new YAMLFactory()).enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        } else {
            objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        }

        Object configuration;

        if (isGraph) {
            ComputationGraphConfiguration.GraphBuilder graphBuilder = new NeuralNetConfiguration.Builder().seed(seed != null ? seed : 12345)
                    // Common builder settings
                    .weightInit(weightInit != null ? weightInit : WeightInit.XAVIER)
                    .updater(updater != null ? updater : new Adam(learningRate != null ? learningRate : 0.001)) // Example default
                    .graphBuilder();


            // Example graph structure - very basic for illustration
            // A real tool would need complex logic to define graph vertices and edges from CLI options
            if (inputTypeSize != null) {
                graphBuilder.addInputs("input1").setInputTypes(InputType.feedForward(inputTypeSize));
            } else if (inputTypeChannels != null && inputTypeHeight != null && inputTypeWidth != null) {
                graphBuilder.addInputs("input1").setInputTypes(InputType.convolutional(inputTypeHeight, inputTypeWidth, inputTypeChannels));
            } else {
                log.warn("Input type not fully specified for graph. Add --inputTypeSize or --inputTypeChannels/Height/Width.");
                graphBuilder.addInputs("input1"); // Default input
            }

            String lastLayerName = "input1";
            for (int i = 0; i < numLayers; i++) {
                String currentLayerName = "dense" + i;
                graphBuilder.addLayer(currentLayerName,
                        new DenseLayer.Builder().nIn(i == 0 && inputTypeSize != null ? inputTypeSize : numHidden) // Simplified nIn logic
                                .nOut(numHidden)
                                .activation(Activation.RELU)
                                .build(),
                        lastLayerName);
                lastLayerName = currentLayerName;
            }
            graphBuilder.addLayer("outputLayer",
                    new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                            .nIn(numLayers > 0 ? numHidden : (inputTypeSize != null ? inputTypeSize : 0)) // Simplified nIn
                            .nOut(numOutputs)
                            .activation(Activation.SOFTMAX)
                            .build(),
                    lastLayerName);
            graphBuilder.setOutputs("outputLayer");

            configuration = graphBuilder.build();
        } else { // NeuralNetConfiguration (MultiLayerNetwork)
            NeuralNetConfiguration.Builder nnBuilder = new NeuralNetConfiguration.Builder();
            if (seed != null) nnBuilder.seed(seed);
            if (weightInit != null) nnBuilder.weightInit(weightInit);
            if (biasInit != null) nnBuilder.biasInit(biasInit);

            IUpdater actualUpdater = (updater != null) ? updater : new Adam(learningRate != null ? learningRate : 0.001);
            if (learningRateSchedule != null && actualUpdater instanceof Adam) {
                ((Adam) actualUpdater).setLearningRateSchedule(learningRateSchedule);
            }
            nnBuilder.updater(actualUpdater);


            //objectMapper.writeValue(outputFile, configuration);
            log.info("{} DL4J training configuration written to: {}", format.toUpperCase(), outputFile.getAbsolutePath());
            return 0;
        }
        return 0;
    }
}