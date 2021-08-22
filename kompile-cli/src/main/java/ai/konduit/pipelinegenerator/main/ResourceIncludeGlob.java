package ai.konduit.pipelinegenerator.main;

import org.nd4j.common.io.ClassPathResource;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;

import java.util.Set;

public class ResourceIncludeGlob {

    public static void main(String...args) {
        if(args.length != 1) {
            System.err.println("Please only include 1 parameter for printing");
            System.exit(1);
        }

        ClassPathResource classPathResource = new ClassPathResource(args[0]);
        if(!classPathResource.exists()) {
            System.err.println("No directory found " + classPathResource.getPath());
            System.exit(1);
        }


        Reflections reflections = new Reflections(args[0], new ResourcesScanner());
        Set<String> resourceList = reflections.getResources(x -> true);
        StringBuilder stringBuilder = new StringBuilder();
        for(String f1 : resourceList) {
            stringBuilder.append(String.format("{\"pattern\":\"\\\\Q%s\\\\E\"},\n",f1));
        }

        System.out.println(stringBuilder.toString());
    }

}
