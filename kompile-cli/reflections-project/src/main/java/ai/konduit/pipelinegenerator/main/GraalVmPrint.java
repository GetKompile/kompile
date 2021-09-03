package ai.konduit.pipelinegenerator.main;

import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.reflections.util.FilterBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "print-class-path", mixinStandardHelpOptions = true,
        description = "Print class path resources or classes with a specific pattern.")
public class GraalVmPrint implements Callable<Integer> {

    @Option(names = {"-p","--pattern"})
    private String pattern;
    @Option(names = {"-r","--print-resources"},description = "Print resources in namespace")
    private boolean printResources;
    @Option(names = {"-c","--print-classes"},description = "Print all classes in namespace")
    private boolean printClasses;
    @Option(names = {"-g","--print-graalvm"},description = "Print graalvm declarations for inclusion in configuration files")
    private boolean printGraalVmDeclarations;
    @Option(names = {"-ic","--include-all-classes"},description = "When printing graalvm configuration for classes include all classes,subclasses,fields,methods in reflection or not")
    private boolean includeAllClasses = false;



    public static void main(String...args) {
        int exitCode = new CommandLine(new GraalVmPrint()).execute(args);
        System.exit(exitCode);
    }

    private String transformPatternToFolders() {
        return pattern.replaceAll("\\.","/");
    }



    @Override
    public Integer call() throws Exception {
        if(printResources) {
            List<ClassLoader> classLoadersList = new LinkedList<>();
            classLoadersList.add(ClasspathHelper.contextClassLoader());
            classLoadersList.add(ClasspathHelper.staticClassLoader());
            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0])))
                    .setScanners(
                            new ResourcesScanner()
                    ));

            Set<String> resources = reflections.getResources(Pattern.compile(".*")).stream()
                    .filter(input -> input.contains(transformPatternToFolders())).collect(Collectors.toSet());
            if(printGraalVmDeclarations)
                resources.stream().forEach(input -> System.out.println(String.format(String.format("    {\"pattern\":\"\\\\Q%s\\\\E\"},\n",input))));
            else {
                resources.stream().forEach(input -> System.out.println(input));

            }
        } else if(printClasses) {
            List<ClassLoader> classLoadersList = new LinkedList<>();
            classLoadersList.add(ClasspathHelper.contextClassLoader());
            classLoadersList.add(ClasspathHelper.staticClassLoader());
            ConfigurationBuilder configurationBuilder = new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage(pattern))
                    .setScanners(new SubTypesScanner(false))
                    .filterInputsBy(new FilterBuilder().includePackage(pattern));
            Reflections reflections = new Reflections(configurationBuilder,new SubTypesScanner(false));
            Set<String> typeList = reflections.getAllTypes()
                    .stream().filter(input -> input.contains(pattern)).collect(Collectors.toSet());
            StringBuilder stringBuilder = new StringBuilder();
            for(String fullyQualfiedClassName : typeList) {
                if(printGraalVmDeclarations) {
                    if(includeAllClasses) {
                        stringBuilder.append(String.format("  {\n" +
                                "    \"name\":\"%s\",\n" +
                                "    \"allDeclaredConstructors\" : true,\n" +
                                "    \"allPublicConstructors\" : true,\n" +
                                "    \"allDeclaredMethods\" : true,\n" +
                                "    \"allPublicMethods\" : true,\n" +
                                "    \"allDeclaredClasses\" : true,\n" +
                                "    \"allPublicClasses\" : true\n" +
                                "  },",fullyQualfiedClassName));
                    }
                    else {
                        stringBuilder.append(String.format(" {\n" +
                                "    \"name\":\"%s\"\n" +
                                "  },\n",fullyQualfiedClassName));
                    }


                } else {
                    stringBuilder.append(fullyQualfiedClassName + "\n");
                }

            }

            System.out.println(stringBuilder.toString());
        }

        return 0;
    }
}
