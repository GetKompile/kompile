package ai.konduit.pipelinegenerator.main.models;

import onnx.Onnx;
import org.tensorflow.framework.GraphDef;
import picocli.CommandLine;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "onnx-print",description = "Print summary of a target dl4j model.")
public class TensorflowPrint implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    public TensorflowPrint() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        GraphDef graphDef = GraphDef.parseFrom(new FileInputStream(modelFile));
        System.out.println(graphDef);

        return 0;
    }
}
