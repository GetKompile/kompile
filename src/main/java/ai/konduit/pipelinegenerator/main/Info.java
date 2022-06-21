package ai.konduit.pipelinegenerator.main;

import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "info",mixinStandardHelpOptions = false,description = "Display information on current kompile installation.")
public class Info implements Callable<Integer> {

    public Info() {
    }

    public static File mavenDirectory() {
        return new File(homeDirectory(),"mvn");
    }

    public static File graalvmDirectory() {
        return new File(homeDirectory(),"graalvm");
    }


    public static File pythonDirectory() {
        return new File(homeDirectory(),"python");
    }
    public static File homeDirectory() {
        return new File(System.getProperty("user.home"),".kompile");
    }

    @Override
    public Integer call() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Kompile SDK information\n");
        File f = new File(System.getProperty("user.home"),".kompile");
        stringBuilder.append("Kompile Home directory location at " + f.getAbsolutePath() + " is installed: " + f.exists());
        stringBuilder.append("\n");
        stringBuilder.append("Graalvm Installed: " + Info.graalvmDirectory().exists());
        stringBuilder.append("\n");
        stringBuilder.append("Maven installed: " + Info.mavenDirectory().exists());
        stringBuilder.append("\n");
        stringBuilder.append("Python installed: " + Info.pythonDirectory().exists());
        System.out.println(stringBuilder);
        return 0;
    }

    public static void main(String...args) throws Exception {
        new Info().call();
    }

}
