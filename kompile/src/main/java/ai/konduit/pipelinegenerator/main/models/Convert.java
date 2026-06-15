/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.models;

import ai.konduit.pipelinegenerator.main.Info;
import ai.konduit.pipelinegenerator.main.install.ArchiveUtils;
import ai.konduit.pipelinegenerator.main.install.InstallMain;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.samediff.frameworkimport.FrameworkImporter;
import org.nd4j.samediff.frameworkimport.onnx.importer.OnnxFrameworkImporter;
import org.nd4j.samediff.frameworkimport.reflect.ClassGraphHolder;
import org.nd4j.samediff.frameworkimport.reflect.ImportReflectionCache;
import org.nd4j.samediff.frameworkimport.tensorflow.importer.TensorflowFrameworkImporter;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "convert",description = "Convert models from tensorflow or onnx to samediff and keras h5 to dl4j zip.")
public class Convert implements Callable<Integer> {

    static {
        try {
            File loadedJson = new File(Info.homeDirectory(),"scanned-classes");
            File scannedClassesFile = new File(loadedJson,"scanned-classes.json");
            if(!scannedClassesFile.exists()) {
                System.out.println("Downloading model import metadata. This process needs to only happen once.");
                File file = InstallMain.downloadTo("https://github.com/KonduitAI/kompile-program-repository/releases/download/scanned-classes.zip/scanned-classes.zip", new File(Info.homeDirectory(), "scanned-classes.zip").getName(), true);
                ArchiveUtils.unzipFileTo(file.getAbsolutePath(),new File(Info.homeDirectory(),"scanned-classes").getAbsolutePath());
                try(InputStream scannedResource = new FileInputStream(new File(Info.homeDirectory() + File.separator + "scanned-classes","scanned-classes.json"))) {
                    String input = IOUtils.toString(scannedResource, Charset.defaultCharset());
                    ClassGraphHolder.loadFromJson(input);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                String input = FileUtils.readFileToString(scannedClassesFile, Charset.defaultCharset());
                ClassGraphHolder.loadFromJson(input);
            }


            ImportReflectionCache.load();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
    @CommandLine.Option(names = {"--format"},description = "Specify: tensorflow, keras or onnx to convert to samediff format. Optional if the specified inputFile is in the correct format (.h5/.hdf5 (keras) .pb (tensorflow) or .onnx (onnx))",required = true)
    private String format;
    @CommandLine.Option(names = {"--inputFile"},description = "Input file for conversion",required = true)
    private File inputFile;
    @CommandLine.Option(names = {"--outputFile"},description = "File output path: optional default will be the original file name minus the format followed by .fb for flatbuffers the samediff format or .zip the dl4j format when converting from keras.",required = false)
    private File outputFile;
    @CommandLine.Option(names = {"--kerasNetworkType"},description = "Specify: sequential or functional ",required = false)
    private String kerasNetworkType;
    @CommandLine.Option(names = {"--unfreeze"},description = "Unfreeze a model before saving it. This will convert all constants to variables.",required = false)
    private boolean unfreeze;


    public Convert() {

    }

    @Override
    public Integer call() throws Exception {
        if(!inputFile.exists()) {
            System.err.println("File: " + inputFile.getAbsolutePath() + " does not exist. Exiting.");
            System.exit(1);
        }

        if(format == null) {
            if(inputFile.getName().endsWith(".pb")) {
                format = "tensorflow";
            } else if(inputFile.getName().endsWith(".h5") || inputFile.getName().endsWith(".hdf5")) {
                format = "keras";
            } else if(inputFile.getName().endsWith(".onnx")) {
                format = "onnx";
            }
        }

        if(format == null) {
            System.err.println("No format specified and unable to infer format from file extension. Please specify a format (onnx,tensorflow,keras) or ensure your input file is a correct extension.");
            return 1;
        }

        String fileName = inputFile.getName().substring(0,inputFile.getName().lastIndexOf('.'));
        if(outputFile == null) {
            if(outputEndSuffixForFormat() == null) {
                System.err.println("Unable to determine output end suffix for output file. Please ensure your network is a valid format.");
                System.exit(1);
            }
            outputFile = new File(fileName + outputEndSuffixForFormat());
        }

        switch(format) {
            case "onnx":
                FrameworkImporter onnxFrameworkImporter = new OnnxFrameworkImporter();
                SameDiff sameDiff = onnxFrameworkImporter.runImport(inputFile.getAbsolutePath(), Collections.emptyMap(), true);
                if(unfreeze)
                    sameDiff.prepareForTraining();
                sameDiff.asFlatFile(outputFile);
                System.out.println("Saved converted file to " + outputFile.getAbsolutePath());
                break;
            case "tensorflow":
                FrameworkImporter tensorflowFrameworkImporter = new TensorflowFrameworkImporter();
                SameDiff sameDiff1 = tensorflowFrameworkImporter.runImport(inputFile.getAbsolutePath(),Collections.emptyMap(),true);
                if(unfreeze)
                    sameDiff1.convertConstantsToVariables();
                sameDiff1.asFlatFile(outputFile);
                System.out.println("Saved converted file to " + outputFile.getAbsolutePath());
                break;
            case "keras":
                if(kerasNetworkType == null) {
                    System.err.println("Please specify a keras network type of either function or sequential if importing from keras h5.");
                    System.exit(1);
                }

                switch(kerasNetworkType) {
                    case "sequential":
                        MultiLayerNetwork multiLayerNetwork = KerasModelImport.importKerasSequentialModelAndWeights(inputFile.getAbsolutePath());
                        multiLayerNetwork.save(outputFile);
                        System.out.println("Saved converted file to " + outputFile.getAbsolutePath());
                        break;
                    case "functional":
                        ComputationGraph computationGraph = KerasModelImport.importKerasModelAndWeights(inputFile.getAbsolutePath(), true);
                        computationGraph.save(outputFile);
                        System.out.println("Saved converted file to " + outputFile.getAbsolutePath());
                        break;
                }

                break;
        }


        return 0;
    }

    private String outputEndSuffixForFormat() {
        switch(format) {
            case "onnx":
            case "tensorflow":
                return ".fb";
            case "keras":
                return ".zip";
            default:
                return null;
        }
    }
}
