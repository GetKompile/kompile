package ai.konduit.pipelinegenerator.main.uninstall;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "all",mixinStandardHelpOptions = false)
public class UnInstallAll implements Callable<Integer> {
    public UnInstallAll() {
    }

    @Override
    public Integer call() throws Exception {
        int exit = new UnInstallGraalvm().call();
        exit = new UnInstallMaven().call();
        exit = new UnInstallPython().call();
        return exit;
    }
}
