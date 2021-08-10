package org.example;

import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.config.BatchOutputConfig;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.util.Collections;

public class TestModel {

    public static void main(String...args) throws Exception {
        SameDiff sameDiff = SameDiff.fromFlatFile(new File("graph.fb"));
        INDArray arr = Nd4j.ones(1,3,224,224);
        sameDiff.batchOutput().inputs(Collections.singletonMap("input", arr))
                .output("output").output();

    }

}
