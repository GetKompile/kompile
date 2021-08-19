package ai.konduit.pipelinegenerator.main;

import org.reflections.Configuration;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PrintJavaCppResources {

    public static void main(String...args) {
        List<ClassLoader> classLoadersList = new LinkedList<>();
        classLoadersList.add(ClasspathHelper.contextClassLoader());
        classLoadersList.add(ClasspathHelper.staticClassLoader());
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0])))
                .setScanners(
            new ResourcesScanner()
        ));

        Set<String> resources = reflections.getResources(Pattern.compile(".*")).stream()
                .filter(input -> input.contains("org/bytedeco")).collect(Collectors.toSet());


        resources.stream().forEach(input -> System.out.println(String.format(String.format("    {\"pattern\":\"\\\\Q%s\\\\E\"},\n",input))));
    }


}
