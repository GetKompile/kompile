package ai.konduit.pipelinegenerator.main.models;

import onnx.Onnx;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "onnx-print",description = "Print summary of a target dl4j model.")
public class OnnxPrint implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    public OnnxPrint() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        Onnx.ModelProto onnxModelProto = Onnx.ModelProto.parseDelimitedFrom(new FileInputStream(modelFile));
        System.out.println(onnxModelProto);

        return 0;
    }
}
