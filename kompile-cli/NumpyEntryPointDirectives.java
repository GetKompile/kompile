package ai.konduit.pipelinegenerator.main;
import org.bytedeco.javacpp.Pointer;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CLongPointer;
import org.graalvm.word.PointerBase;
import org.nd4j.common.io.ClassPathResource;
import org.nd4j.linalg.api.memory.AllocationsTracker;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class NumpyEntryPointDirectives implements CContext.Directives {



    @Override
    public List<String> getHeaderFiles() {
        /*
         * The header file with the C declarations that are imported. We use a helper class that
         * locates the file in our project structure.
         */
        try {
            return Collections.singletonList("\"" + new ClassPathResource("numpy_struct.h").getFile().getAbsolutePath() + "\"");
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
