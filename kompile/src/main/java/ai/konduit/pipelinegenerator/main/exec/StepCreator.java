/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.exec;

import ai.konduit.pipelinegenerator.main.PipelineStepType;
import ai.konduit.pipelinegenerator.main.converter.*;
import ai.konduit.serving.data.image.convert.ImageToNDArrayConfig;
import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.pipeline.api.data.Point;
import ai.konduit.serving.pipeline.api.step.PipelineStep;
import io.swagger.v3.oas.annotations.media.Schema;
import org.nd4j.linalg.learning.config.IUpdater;
import org.nd4j.linalg.schedule.ISchedule;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "step-create",description = "Creates various  konduit serving steps")
public class StepCreator implements CommandLine.IModelTransformer, Callable<Void> {


    public StepCreator(){}

    private Map<String, CommandLine.ITypeConverter> converters = new HashMap<>();


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
        CommandLine commandLine = new CommandLine(new StepCreator());
        commandLine.usage(System.err);
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
            CommandLine.ParseResult parseResult2 = parseResult;
            while(!parseResult2.commandSpec().name().equals("step-create")) {
                parseResult2 = parseResult2.subcommand();
            }

            parseResult2.commandSpec().commandLine().usage(System.err);

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
        converters.put(IUpdater.class.getName(),new IUpdaterTypeConverter());
        converters.put(ISchedule.class.getName(),new IScheduleTypeConverter());
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
        CommandLine.ParseResult result = parseResult;
        if(subcommand.subcommand() == null) {
            return null;
        }

        String name = subcommand.commandSpec().name();

        CommandLine.ParseResult lastSubCommand = subcommand;
        while(subcommand.subcommand() != null) {
            name = subcommand.subcommand().commandSpec().name();
            subcommand = subcommand.subcommand();
        }

        //return null when no params are available
        if(name.equals("step-create") || name.equals("-h")) {
            return null;
        }

        return getPipelineStep(subcommand, name);
    }

    private static PipelineStep getPipelineStep(CommandLine.ParseResult subcommand, String name) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        PipelineStepType pipelineStepType = PipelineStepType.valueOf(name.toUpperCase());
        Class<? extends PipelineStep> aClass = PipelineStepType.clazzForType(pipelineStepType);
        PipelineStep ret =  aClass.newInstance();
        for(Field field : aClass.getDeclaredFields()) {
            field.setAccessible(true);
            if(subcommand != null && subcommand.hasMatchedOption("--" + field.getName())) {
                CommandLine.Model.OptionSpec optionSpec = subcommand.matchedOption("--" + field.getName());
                Object value = optionSpec.getValue();
                field.set(ret,value);
            }
        }

        return ret;
    }



}
