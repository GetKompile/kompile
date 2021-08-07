package ai.konduit.pipelinegenerator.main;

import org.bytedeco.numpy.global.numpy;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.bytedeco.cpython.global.python.Py_Initialize;
import static org.bytedeco.cpython.helper.python.Py_AddPath;

@CommandLine.Command(name = "print-python-path",mixinStandardHelpOptions = true)
public class PrintJavacppPythonPath implements Callable<Void> {
    @Override
    public Void call() throws Exception {
        System.out.println(Arrays.stream(numpy.cachePackages()).map(input -> input.getAbsolutePath()).collect(Collectors.joining(File.pathSeparator)));
        return null;
    }
}
