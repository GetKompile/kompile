package ai.konduit.pipelinegenerator.main;

import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.api.ops.impl.shape.Concat;

public class PrintOpHashes {

    public static void main(String...args) {
        new Concat().opHash();
    }

}
