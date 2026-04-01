package ai.kompile.cli.main.sdk;

import picocli.CommandLine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "serve",
        mixinStandardHelpOptions = true,
        description = "Launch an OpenAI-compatible API server for local LLM inference.\n" +
                "Serves a SameDiff/DL4J model via the OpenAI Chat Completions API.")
public class SdkServe implements Callable<Integer> {

    @CommandLine.Option(names = {"--model-path"}, required = true,
            description = "Path to HuggingFace-format model directory")
    private String modelPath;

    @CommandLine.Option(names = {"--port"}, defaultValue = "8080",
            description = "Server port (default: ${DEFAULT-VALUE})")
    private int port;

    @CommandLine.Option(names = {"--host"}, defaultValue = "0.0.0.0",
            description = "Server bind host (default: ${DEFAULT-VALUE})")
    private String host;

    @CommandLine.Option(names = {"--temperature"}, defaultValue = "0.7",
            description = "Default temperature (default: ${DEFAULT-VALUE})")
    private double temperature;

    @CommandLine.Option(names = {"--max-tokens"}, defaultValue = "256",
            description = "Default max tokens (default: ${DEFAULT-VALUE})")
    private int maxTokens;

    @CommandLine.Option(names = {"--chat-template"},
            description = "Chat template type: auto, chatml, llama2, vicuna, alpaca (default: auto)")
    private String chatTemplate = "auto";

    @CommandLine.Option(names = {"--model-id"},
            description = "Model identifier for API responses (default: directory name)")
    private String modelId;

    @Override
    public Integer call() throws Exception {
        // Try to use the serving module directly if on classpath
        try {
            Class<?> serverClass = Class.forName("ai.kompile.serving.openai.OpenAiCompatibleServer");
            List<String> args = buildArgs();
            CommandLine cmd = new CommandLine(serverClass.getDeclaredConstructor().newInstance());
            return cmd.execute(args.toArray(new String[0]));
        } catch (ClassNotFoundException e) {
            // Fall back to launching as subprocess
            return launchSubprocess();
        }
    }

    private List<String> buildArgs() {
        List<String> args = new ArrayList<>();
        args.add("--model-path");
        args.add(modelPath);
        args.add("--port");
        args.add(String.valueOf(port));
        args.add("--host");
        args.add(host);
        args.add("--temperature");
        args.add(String.valueOf(temperature));
        args.add("--max-tokens");
        args.add(String.valueOf(maxTokens));
        if (chatTemplate != null) {
            args.add("--chat-template");
            args.add(chatTemplate);
        }
        if (modelId != null) {
            args.add("--model-id");
            args.add(modelId);
        }
        return args;
    }

    private int launchSubprocess() throws Exception {
        // Find the shaded JAR
        String kompileHome = System.getProperty("user.home") + File.separator + ".kompile";
        File shadedJar = findShadedJar(kompileHome);

        if (shadedJar == null) {
            System.err.println("Error: kompile-sdk-serving JAR not found.");
            System.err.println("Build it with: cd kompile-sdk-serving && mvn clean package");
            return 1;
        }

        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-jar");
        command.add(shadedJar.getAbsolutePath());
        command.addAll(buildArgs());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();

        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));

        return process.waitFor();
    }

    private File findShadedJar(String kompileHome) {
        // Check common locations
        String[] searchPaths = {
                "kompile-sdk-serving/target",
                kompileHome + "/lib",
                "."
        };

        for (String path : searchPaths) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                File[] matches = dir.listFiles((d, name) ->
                        name.startsWith("kompile-sdk-serving") && name.endsWith("-shaded.jar"));
                if (matches != null && matches.length > 0) {
                    return matches[0];
                }
            }
        }
        return null;
    }

    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            return javaHome + File.separator + "bin" + File.separator + "java";
        }
        return "java";
    }
}
