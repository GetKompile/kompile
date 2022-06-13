package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.data.image.convert.ImageToNDArrayConfig;
import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.pipeline.api.data.Point;
import ai.konduit.serving.pipeline.api.step.PipelineStep;
import io.swagger.v3.oas.annotations.media.Schema;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command
public class StepCreator implements CommandLine.IModelTransformer, Callable<Void> {


    public StepCreator(){}

    private Map<String, CommandLine.ITypeConverter> converters = new HashMap<>();
    @CommandLine.Parameters
    private List<String> params;
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec; // injected by picocli

    @Override
    public CommandLine.Model.CommandSpec transform(CommandLine.Model.CommandSpec commandSpec) {
        try {
            CommandLine.Model.CommandSpec spec = spec();
            commandSpec.addSubcommand("step-create",spec);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return commandSpec;
    }

    @Override
    public Void call() throws Exception {

        return null;
    }

    public static int run(CommandLine.ParseResult parseResult) throws Exception {
        //this is needed to ensure that we process the help command. Since this is a custom
        //execution strategy, the command output might be inconsistent with the rest of the cli
        //without some manual intervention
        if(parseResult.subcommand() != null && parseResult.subcommand().subcommand() != null) {
            if(parseResult.subcommand().subcommand().hasMatchedOption("-h") || parseResult.subcommand().subcommand().hasMatchedOption("--help")) {
                parseResult.subcommand().subcommand().commandSpec().commandLine().usage(System.err);
                return 1;
            }
        }
        PipelineStep stepFromResult = createStepFromResult(parseResult);
        //same as above: if a user passes the help signal, this method returns null
        if(stepFromResult == null) {
            parseResult.subcommand().commandSpec().commandLine().usage(System.err);
            return 1;
        }
        CommandLine.Model.OptionSpec optionSpec = parseResult.matchedOption("--fileFormat");
        String fileFormat = optionSpec == null ? "json" : optionSpec.getValue();
        if(fileFormat.equals("json")) {
            System.out.println(stepFromResult.toJson());
        } else if(fileFormat.equals("yaml") || fileFormat.equals("yml")) {
            System.out.println(stepFromResult.toYaml());
        }

        return 0;
    }

    private enum GraphStepType {
        SWITCH,
        MERGE,
        ANY
    }


    private void registerConverters() {
        converters.put(ImageToNDArrayConfig.class.getName(),new ImageToNDArrayConfigTypeConverter());
        converters.put(Point.class.getName(),new PointConverter());
        converters.put(PythonConfig.class.getName(),new PythonConfigTypeConverter());
    }


    public CommandLine.Model.CommandSpec spec() throws Exception {
        registerConverters();
        CommandLine.Model.CommandSpec ret = CommandLine.Model.CommandSpec.create();
        for(PipelineStepType pipelineStepType : PipelineStepType.values()) {
            Class<? extends PipelineStep> aClass = PipelineStepType.clazzForType(pipelineStepType);
            if(aClass != null) {
                CommandLine.Model.CommandSpec spec = CommandLine.Model.CommandSpec.create();
                spec.mixinStandardHelpOptions(false); // usageHelp and versionHelp option
                addStep(PipelineStepType.clazzForType(pipelineStepType),spec);
                spec.name(pipelineStepType.name());
                spec.addOption(CommandLine.Model.OptionSpec.builder("--fileFormat")
                        .type(String.class)
                        .required(true)
                        .description("The file format (either json or yaml/yml) to output the pipeline step in")
                        .build());

                ret.addSubcommand(pipelineStepType.name().toLowerCase(),spec);
            } else {
                System.err.println("No class found for " + pipelineStepType);
            }
        }

        ret.name("step-create");
        ret.mixinStandardHelpOptions(false);
        return ret;
    }

    public  void addStep(Class<? extends PipelineStep> clazz,CommandLine.Model.CommandSpec spec) {
        for(Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            CommandLine.Model.OptionSpec.Builder builder = CommandLine
                    .Model.OptionSpec.builder("--" + field.getName())
                    .type(field.getType());
            if(converters.containsKey(field.getType().getName())) {
                builder.converters(converters.get(field.getType().getName()));
            }
            if(field.isAnnotationPresent(Schema.class)) {
                Schema annotation = field.getAnnotation(Schema.class);
                builder.description(annotation.description());
            }


            spec.addOption(builder.build());
        }

    }

    public static PipelineStep createStepFromResult(CommandLine.ParseResult parseResult) throws Exception {
        CommandLine.ParseResult subcommand = parseResult.subcommand();
        if(subcommand.subcommand() == null) {
            return null;
        }
        String name = subcommand.subcommand().commandSpec().name();
        return getPipelineStep(subcommand.subcommand(), name);
    }

    private static PipelineStep getPipelineStep(CommandLine.ParseResult subcommand, String name) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        PipelineStepType pipelineStepType = PipelineStepType.valueOf(name.toUpperCase());
        Class<? extends PipelineStep> aClass = PipelineStepType.clazzForType(pipelineStepType);
        PipelineStep ret =  aClass.newInstance();
        for(Field field : aClass.getDeclaredFields()) {
            field.setAccessible(true);
            if(subcommand.hasMatchedOption("--" + field.getName())) {
                CommandLine.Model.OptionSpec optionSpec = subcommand.matchedOption("--" + field.getName());
                Object value = optionSpec.getValue();
                field.set(ret,value);
            }
        }

        return ret;
    }



}
