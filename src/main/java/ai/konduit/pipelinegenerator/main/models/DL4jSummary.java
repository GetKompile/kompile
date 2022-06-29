package ai.konduit.pipelinegenerator.main.models;

import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "dl4j-summary",description = "Print summary of a target dl4j model.")
public class DL4jSummary implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelType"},description = "Model type: cg or mln. CG is shorthand for computation graph and mln is short hand for MultiLayerNetwork respectively.",required = true)
    private String modelType;

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    public DL4jSummary() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            System.exit(1);
        }

        switch(modelType) {
            case "mln":
                MultiLayerNetwork multiLayerNetwork = MultiLayerNetwork.load(new File(modelInputPath),false);
                System.out.println(multiLayerNetwork.summary());
                break;
            case "cg":
                ComputationGraph computationGraph = ComputationGraph.load(modelFile,false);
                System.out.println(computationGraph.summary());
                break;
        }

        return 0;
    }
}
