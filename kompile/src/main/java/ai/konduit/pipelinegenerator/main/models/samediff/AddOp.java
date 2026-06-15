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

package ai.konduit.pipelinegenerator.main.models.samediff;

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.codegen.Namespace;
import org.nd4j.codegen.api.Arg;
import org.nd4j.codegen.api.Input;
import org.nd4j.codegen.api.LossReduce;
import org.nd4j.codegen.api.Op;
import org.nd4j.linalg.api.buffer.DataType;
import picocli.CommandLine;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "add-op",description = "Add op to an existing samediff model.")
public class AddOp implements CommandLine.IModelTransformer, Callable<Integer> {

    private static Map<String,Op> ops = new LinkedHashMap<>();


    public AddOp() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new AddOp());
        commandLine.usage(System.err);
        return 0;
    }



    public static CommandLine.Model.CommandSpec spec() {
        List<String> namespaces = Arrays.stream(Namespace.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList());
        CommandLine.Model.CommandSpec ret = CommandLine.Model.CommandSpec.create();
        ret.name("add-op");
        CommandLine.Model.UsageMessageSpec usageMessageSpec = new CommandLine.Model.UsageMessageSpec();
        usageMessageSpec.description("Adds an op to an existing samediff model.");
        ret.usageMessage(usageMessageSpec);

        for (String s : namespaces) {
            Namespace ns = Namespace.fromString(s.toUpperCase());
            CommandLine.Model.CommandSpec subCommand = CommandLine.Model.CommandSpec.create();
            Map<String, Op> ops = new HashMap<>();
            ns.getNamespace().getOps().forEach(op -> {
                if(ops.containsKey(op.name())) {
                    //add only the maximum number of arguments as the final op to be processed
                    Op op2 = ops.get(op.name());
                    int paramsSize = op2.getArgs().size() + op2.inputs().size();
                    int currOpSize = op.getArgs().size() + op.getInputs().size();
                    if(paramsSize > currOpSize) {
                        ops.put(op.name(),op2);
                    }

                }
                else ops.put(op.name(),op);
            });

            ops.values().forEach(op -> {
                CommandLine.Model.CommandSpec commandSpec1 = CommandLine.Model.CommandSpec.create();
                op.inputs().forEach(input -> {
                    commandSpec1.addOption(CommandLine.Model.OptionSpec.builder("--" + input.getName())
                            .type(String.class)
                            .required(true)
                            .description(input.getDescription())
                            .build());
                });

                op.getArgs().forEach(arg -> {
                    CommandLine.Model.OptionSpec.Builder builder = CommandLine.Model.OptionSpec.builder("--" + arg.getName())
                            .description(arg.getDescription());

                    switch (arg.getType()) {
                        case INT:
                            builder.type(Integer.class);
                            break;
                        case BOOL:
                            builder.type(Boolean.class);
                            break;
                        case ENUM:
                            break;
                        case LONG:
                            builder.type(Long.class);
                            break;
                        case STRING:
                            builder.type(String.class);
                            break;
                        case NDARRAY:
                            break;
                        case NUMERIC:
                            break;
                        case CONDITION:
                            break;
                        case DATA_TYPE:
                            builder.type(DataType.class);
                            break;
                        case LOSS_REDUCE:
                            builder.type(LossReduce.class);
                            break;
                        case FLOATING_POINT:
                            builder.type(Double.class);
                            break;
                    }


                    CommandLine.Model.UsageMessageSpec usageMessageSpec1 = new CommandLine.Model.UsageMessageSpec();
                    usageMessageSpec1.description(op.getDoc().stream().map(input -> input.getText()).collect(Collectors.joining()));
                    commandSpec1.usageMessage(usageMessageSpec1);
                    builder.required(arg.getDefaultValue() == null);

                    if (arg.getDefaultValue() != null) {
                        builder.defaultValue(arg.getDefaultValue().toString());
                    }

                    commandSpec1.addOption(builder.build());
                });

                commandSpec1.addOption(CommandLine.Model.OptionSpec.builder("--modelInputPath")
                        .required(true).description("Input path to model to add op to")
                        .type(File.class)
                        .build());


                commandSpec1.addOption(CommandLine.Model.OptionSpec.builder("--outputVariableName")
                        .required(false)
                        .description("Optional output variable name for the op")
                        .type(String.class)
                        .build());

                commandSpec1.addOption(CommandLine.Model.OptionSpec.builder("--modelOutputPath")
                        .required(true)
                        .type(File.class)
                        .build());


                subCommand.addSubcommand(op.name(), commandSpec1);

            });

            ret.addSubcommand(ns.name().toLowerCase(), subCommand);
            //accumulate final ops by name for later use
            AddOp.ops.putAll(ops);

        }


        return ret;
    }


    public static int run(CommandLine.ParseResult parseResult) throws Exception {
        //this is needed to ensure that we process the help command. Since this is a custom
        //execution strategy, the command output might be inconsistent with the rest of the cli
        //without some manual intervention
        //this is needed to ensure that we process the help command. Since this is a custom
        //execution strategy, the command output might be inconsistent with the rest of the cli
        //without some manual intervention
        if(parseResult.subcommand() != null && parseResult.subcommand().subcommand() != null) {
            if(parseResult.subcommand().subcommand().hasMatchedOption("-h") || parseResult.subcommand().subcommand().hasMatchedOption("--help")) {
                parseResult.subcommand().subcommand().commandSpec().commandLine().usage(System.err);
                return 1;
            }
        }


        while(!parseResult.commandSpec().name().equals("add-op")) {
            parseResult = parseResult.subcommand();
        }

        CommandLine.ParseResult namespace = parseResult.subcommand();
        String namespaceName = namespace.commandSpec().name();
        CommandLine.ParseResult op = namespace.subcommand();


        File inputPath = op.matchedOption("--modelInputPath").getValue();
        SameDiff sameDiff = SameDiff.load(inputPath,true);
        File outputPath = op.matchedOption("--modelOutputPath").getValue();
        //actual function name does not match namespace it's an abbreviation
        if(namespaceName.equals("neuralnetwork"))
            namespaceName = "nn";

        Method namespaceRet = sameDiff.getClass().getDeclaredMethod(namespaceName);
        //get the namespace associated with this function to invoke
        Object namespaceToInvoke = namespaceRet.invoke(sameDiff);
        Method method = methodWithName(op.commandSpec().name(),namespaceToInvoke.getClass());


        Op opToUse = ops.get(op.commandSpec().name());
        Object[] args = new Object[method.getParameters().length];
        int argIndex = 0;
        String outputVariableName = op.hasMatchedOption("--outputVariableName")  ? op.matchedOption("--outputVariableName").getValue().toString()
                : null;
        args[argIndex] = outputVariableName;

        if(outputVariableName != null && sameDiff.hasVariable(outputVariableName)) {
            System.err.println("Found specified output variable name in graph: " + outputVariableName + " Exiting.");
            return 1;
        }

        argIndex++;
        for(Input input : opToUse.getInputs()) {
            CommandLine.Model.OptionSpec optionSpec = op.matchedOption("--" + input.name());
            SDVariable variable = sameDiff.getVariable(optionSpec.getValue().toString());
            if(variable == null) {
                System.err.println("No variable found with name " + optionSpec.getValue().toString());
                return 1;
            }
            args[argIndex] = variable;
            argIndex++;
        }

        for(Arg arg : opToUse.getArgs()) {
            CommandLine.Model.OptionSpec optionSpec = op.matchedOption("--" + arg.name());
            if(optionSpec == null) {
                System.err.println("Arg name " + arg.name() + " not found in options!");
                return 1;
            }
            if(optionSpec.getValue() != null) {
                args[argIndex] = optionSpec.getValue();
                argIndex++;
            }

        }

        System.out.println("Invoking method " + method.getName() + " with args " + Arrays.toString(args));

       Object output =  method.invoke(namespaceToInvoke, args);
       if(output instanceof SDVariable) {
           SDVariable newOutput = (SDVariable) output;
           System.out.println("Output variables from op " + method.getName() + newOutput);

       } else if(output instanceof SDVariable[]) {
           SDVariable[] outputs2 = (SDVariable[]) output;
           System.out.println("Output variables from op " + method.getName() + Arrays.toString(outputs2));

       }



        sameDiff.asFlatFile(outputPath);


        return 0;
    }

    private static Method methodWithName(String name,Class<?> clazz) {
        List<Method> collect = Arrays.stream(clazz.getDeclaredMethods()).filter(input -> input.getName().equals(name))
                .collect(Collectors.toList());
        int maxParams = 0;
        Method finalMethod = null;
        for(Method method : collect) {
            if(finalMethod == null) {
                finalMethod = method;
                maxParams = finalMethod.getParameterCount();
            } else if(method.getParameterCount() > maxParams) {
                finalMethod = method;
                maxParams = method.getParameterCount();
            }
        }

        return finalMethod;
    }


    @Override
    public CommandLine.Model.CommandSpec transform(CommandLine.Model.CommandSpec commandSpec) {
        try {
            CommandLine.Model.CommandSpec spec = spec();
            commandSpec.addSubcommand("add-op",spec);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return commandSpec;
    }
}
