package ai.konduit.pipelinegenerator.main.konvert;

import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter;
import org.nd4j.samediff.frameworkimport.tensorflow.importer.TensorflowFrameworkImporter;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "konvert",mixinStandardHelpOptions = true)
public class Konvert implements Callable<Void> {

    @CommandLine.Option(names = {"--framework"},description = "The framework to convert. Defaults to guess which means infer from file extension. .onnx for onnx, .pb for tensorflow, and .h5 for keras")
    private Framework framework = Framework.GUESS;
    @CommandLine.Option(names = {"--kerasImportModelType"},description = "The type of model to import for keras: graph, sequential")
    private KerasImportModelType kerasImportModelType = KerasImportModelType.FUNCTIONAL;
    @CommandLine.Option(names = {"--inputPath"},description = "The input model")
    private File inputPath;
    @CommandLine.Option(names = {"--outputPath"},description = "The output model")
    private File outputPath;

    @Override
    public Void call() throws Exception {
        switch(framework) {
            case GUESS:
                if(inputPath.getAbsolutePath().endsWith(".h5")) {
                    importAndExportKeras();
                } else if(inputPath.getAbsolutePath().endsWith(".pb")) {
                    importAndExportTensorflow();

                } else if(inputPath.getAbsolutePath().endsWith(".onnx")) {
                    importAndExportOnnx();
                }
                break;
            case ONNX:
                importAndExportOnnx();
                break;
            case KERAS:
                importAndExportKeras();
                break;
            case TENSORFLOW:
                importAndExportTensorflow();
                break;
        }

        return null;
    }

    private void importAndExportTensorflow() throws IOException {
        TensorflowFrameworkImporter tensorflowFrameworkImporter = new TensorflowFrameworkImporter();
        tensorflowFrameworkImporter.runImport(inputPath.getAbsolutePath(), Collections.emptyMap(),true).asFlatFile(outputPath);
    }


    private void importAndExportKeras() throws IOException, UnsupportedKerasConfigurationException, InvalidKerasConfigurationException {
       switch(kerasImportModelType) {
           case FUNCTIONAL:
               KerasModelImport.importKerasSequentialModelAndWeights(inputPath.getAbsolutePath(),true).save(outputPath);
               break;
           case SEQUENTIAL:
               KerasModelImport.importKerasModelAndWeights(inputPath.getAbsolutePath(),true).save(outputPath);
               break;
       }
    }

    private void importAndExportOnnx() throws IOException {
        OnnxFrameworkImporter onnxFrameworkImporter = new OnnxFrameworkImporter();
        onnxFrameworkImporter.runImport(inputPath.getAbsolutePath(), Collections.emptyMap(),true).asFlatFile(outputPath);
    }
}
