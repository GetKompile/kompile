package ai.kompile.cli.main.exec;

import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.io.FileUtils;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "create-step",
        aliases = {"new-step"},
        description = "Creates a Kompile pipeline step configuration either interactively or directly via dynamically generated subcommands for available step runner types.",
        mixinStandardHelpOptions = true,
        modelTransformer = NewStepCreator.class
)
public class NewStepCreator implements Callable<Integer>, CommandLine.IModelTransformer {

    @Option(names = {"-o", "--output"}, description = "Output file path for the step configuration. If not set for a subcommand, prints to STDOUT.", scope = CommandLine.ScopeType.INHERIT)
    private File outputFile;

    @Option(names = {"-f", "--format"}, description = "Output format: json or yaml (Default: json). Applies to interactive mode and subcommands.", defaultValue = "json", scope = CommandLine.ScopeType.INHERIT)
    private String outputFormat;

    private final SchemaRegistry schemaRegistry = SchemaRegistry.getInstance();
    private final Scanner scanner = new Scanner(System.in);

    private static final Object TRANSFORMED_MARKER_KEY = NewStepCreator.class;


    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec; // Injected by Picocli

    @Override
    public CommandLine.Model.CommandSpec transform(CommandLine.Model.CommandSpec rootCommandSpec) {
        if (rootCommandSpec.userObject() == TRANSFORMED_MARKER_KEY) {
            return rootCommandSpec;
        }

        List<StepSchema> availableSchemas = getAvailableSchemas();
        if (availableSchemas.isEmpty()) {
            System.err.println("CLI Transform: No step schemas found. No dynamic subcommands will be created for 'create-step'.");
        }

        for (StepSchema schema : availableSchemas) {
            String runnerClassName = schema.getRunnerClassName();
            String subcommandName = generateSubcommandName(runnerClassName);

            if (rootCommandSpec.subcommands().containsKey(subcommandName)) {
                System.err.println("Warning: Subcommand '" + subcommandName + "' already exists or a name collision occurred for runner: " + runnerClassName + ". Skipping duplicate subcommand generation.");
                continue;
            }

            CommandLine.Model.CommandSpec subSpec = CommandLine.Model.CommandSpec.create();
            subSpec.name(subcommandName);
            subSpec.helpCommand(rootCommandSpec.helpCommand());
            subSpec.mixinStandardHelpOptions(true);
            String schemaDescription = schema.getDescription();
            subSpec.parent(rootCommandSpec);

            for (ParameterSchema paramSchema : schema.getParameters()) {
                CommandLine.Model.OptionSpec.Builder optBuilder = CommandLine.Model.OptionSpec.builder("--" + paramSchema.getName())
                        .type(mapKpfTypeToPicocliType(paramSchema.getType(), paramSchema.getListElementType()))
                        .description(buildOptionDescription(paramSchema))
                        .required(paramSchema.isRequired());

                if (paramSchema.getType() == ValueType.LIST) {
                    optBuilder.arity("0..*");
                }

                Object rawDefaultValue = paramSchema.getDefaultValue();
                if (rawDefaultValue != null && isPicocliDefaultCompatible(paramSchema.getType())) {
                    optBuilder.defaultValue(rawDefaultValue.toString());
                }
                subSpec.addOption(optBuilder.build());
            }
            rootCommandSpec.addSubcommand(subcommandName, subSpec);
        }

        return rootCommandSpec;
    }

    private String generateSubcommandName(String runnerClassName) {
        String simpleName = runnerClassName.substring(runnerClassName.lastIndexOf('.') + 1);
        if (simpleName.endsWith("Runner")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Runner".length());
        }
        String kebabCase = simpleName.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
        String sanitized = kebabCase.replaceAll("[^a-z0-9-]", "");
        if (sanitized.isEmpty() || sanitized.equals("-")) {
            String[] parts = runnerClassName.toLowerCase().split("\\.");
            if (parts.length > 1) {
                sanitized = parts[parts.length-2] + "-" + parts[parts.length-1];
                sanitized = sanitized.replaceAll("runner", "").replaceAll("[^a-z0-9-]", "");
                if(sanitized.endsWith("-") && sanitized.length() > 1) sanitized = sanitized.substring(0, sanitized.length() -1);
                if(sanitized.startsWith("-") && sanitized.length() > 1) sanitized = sanitized.substring(1);
            } else {
                sanitized = runnerClassName.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
            }
        }
        return sanitized.isEmpty() ? "step-" + UUID.randomUUID().toString().substring(0,8) : sanitized;
    }

    private List<StepSchema> getAvailableSchemas() {
        // This method uses the instance field schemaRegistry
        List<StepSchema> schemas = new ArrayList<>(this.schemaRegistry.getAllSchemas());
        Set<String> retrievedRunnerClasses = schemas.stream()
                .map(StepSchema::getRunnerClassName)
                .collect(Collectors.toSet());
        ServiceLoader<PipelineStepRunnerFactory> factories = ServiceLoader.load(PipelineStepRunnerFactory.class);
        for (PipelineStepRunnerFactory factory : factories) {
            try {
                String runnerType = factory.getRunnerType();
                if (runnerType != null && !runnerType.trim().isEmpty() && !retrievedRunnerClasses.contains(runnerType)) {
                    Optional<StepSchema> schemaOptional = this.schemaRegistry.getSchema(runnerType);
                    if (schemaOptional.isPresent()) {
                        schemas.add(schemaOptional.get());
                        retrievedRunnerClasses.add(runnerType);
                    } else {
                        System.err.println("Warning: CLI - Schema not found in registry for runner type from factory: " + runnerType);
                    }
                }
            } catch (Exception e) {
                System.err.println("Warning: CLI - Error processing factory " + factory.getClass().getName() + ": " + e.getMessage());
            }
        }
        if (schemas.isEmpty()) {
            System.err.println("CLI: No pipeline step schemas found. Dynamic subcommands might not be available.");
        }
        schemas.sort(Comparator.comparing(StepSchema::getRunnerClassName, String.CASE_INSENSITIVE_ORDER));
        return schemas;
    }

    // Made static as it doesn't depend on instance state
    private static Class<?> mapKpfTypeToPicocliType(ValueType kpfType, ValueType listElementType) {
        if (kpfType == ValueType.LIST) {
            return (listElementType != null) ? mapKpfTypeToPicocliType(listElementType, null) : String.class;
        }
        switch (kpfType) {
            case STRING: return String.class;
            case INT64: return Long.class;
            case BOOLEAN: return boolean.class;
            case DOUBLE: return Double.class;
            case DATA: return String.class;
            case BYTES: return String.class;
            case IMAGE: case NDARRAY: case POINT: case BOUNDING_BOX:
                return String.class;
            default:
                System.err.println("Warning: Unhandled KPF ValueType '" + kpfType + "' in Picocli type mapping. Defaulting to String.class.");
                return String.class;
        }
    }

    // Made static as it doesn't depend on instance state
    private static String buildOptionDescription(ParameterSchema paramSchema) {
        StringBuilder desc = new StringBuilder();
        String paramDesc = paramSchema.getDescription();
        if (paramDesc != null && !paramDesc.isEmpty()) {
            desc.append(paramDesc);
        } else {
            desc.append("Value for parameter '").append(paramSchema.getName()).append("'.");
        }

        String typeDisplay = paramSchema.getType().toString();
        if (paramSchema.getType() == ValueType.LIST && paramSchema.getListElementType() != null) {
            typeDisplay += "<" + paramSchema.getListElementType().toString() + ">";
        }
        desc.append(String.format(" (Type: %s)", typeDisplay));

        Object rawDefault = paramSchema.getDefaultValue();
        if (rawDefault != null) {
            desc.append(String.format(" (Default: %s)", rawDefault.toString()));
        }
        List<String> allowed = paramSchema.getAllowedValues();
        if (allowed != null && !allowed.isEmpty()) {
            desc.append(String.format(" (Allowed: %s)", String.join(", ", allowed)));
        }
        return desc.toString();
    }

    // Made static as it doesn't depend on instance state
    private static boolean isPicocliDefaultCompatible(ValueType type) {
        switch (type) {
            case STRING: case INT64: case BOOLEAN: case DOUBLE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public Integer call() throws Exception {
        CommandLine.ParseResult parseResult = spec.commandLine().getParseResult();
        if (parseResult.subcommand() != null) {
            // If a subcommand is called, outputFile and outputFormat are inherited or taken from subcommand's own options.
            // The handleSubcommandCall method now uses this.outputFile and this.outputFormat (instance fields).
            return handleSubcommandCall(parseResult.subcommand());
        } else {
            return runInteractiveMode();
        }
    }

    private Integer handleSubcommandCall(CommandLine.ParseResult subcommandParseResult) throws IOException {
        String subcommandName = subcommandParseResult.commandSpec().name();

        // To find the runnerClassName, we iterate through available schemas (using instance method)
        // and match by the generated subcommandName.
        Optional<StepSchema> selectedSchemaOpt = this.getAvailableSchemas().stream()
                .filter(s -> generateSubcommandName(s.getRunnerClassName()).equals(subcommandName))
                .findFirst();

        if (!selectedSchemaOpt.isPresent()) {
            System.err.println("Error: Could not find schema for subcommand '" + subcommandName + "'. This indicates an internal inconsistency or schema loading issue.");
            return 1;
        }
        StepSchema selectedSchema = selectedSchemaOpt.get();
        String runnerClassName = selectedSchema.getRunnerClassName();

        Map<String, Object> paramsMap = new HashMap<>();
        for (ParameterSchema paramSchema : selectedSchema.getParameters()) {
            String paramCliName = "--" + paramSchema.getName();
            Object valueFromCli = null;

            if (subcommandParseResult.hasMatchedOption(paramCliName)) {
                valueFromCli = subcommandParseResult.matchedOptionValue(paramCliName, null);
                if (valueFromCli instanceof String) {
                    ValueType expectedType = paramSchema.getType();
                    if (expectedType == ValueType.DATA || expectedType == ValueType.BYTES ||
                            (expectedType == ValueType.LIST && (paramSchema.getListElementType() == ValueType.DATA || paramSchema.getListElementType() == ValueType.BYTES )) ||
                            expectedType == ValueType.IMAGE || expectedType == ValueType.NDARRAY || expectedType == ValueType.POINT || expectedType == ValueType.BOUNDING_BOX
                    ) {
                        try {
                            // Use the instance method convertValue
                            valueFromCli = this.convertValue((String) valueFromCli, expectedType, paramSchema.getName(), paramSchema.getListElementType());
                        } catch (IllegalArgumentException e) {
                            System.err.println("Error parsing CLI value for parameter '" + paramSchema.getName() + "': " + e.getMessage());
                            if(paramSchema.isRequired()) {
                                System.err.println("Required parameter '" + paramSchema.getName() + "' could not be parsed from input: " + valueFromCli);
                                return 1;
                            }
                            valueFromCli = null;
                        }
                    }
                }
                else if (paramSchema.getType() == ValueType.LIST && valueFromCli instanceof List) {
                    List<?> rawList = (List<?>) valueFromCli;
                    List<Object> convertedList = new ArrayList<>();
                    boolean conversionErrorInList = false;
                    for (Object item : rawList) {
                        if (item instanceof String) { // Assuming list elements from CLI that need conversion are strings
                            try {
                                // Use the instance method convertValue
                                convertedList.add(this.convertValue((String) item, paramSchema.getListElementType(), paramSchema.getName() + "[item]", null));
                            } catch (IllegalArgumentException e) {
                                System.err.println("Error in list item for '" + paramSchema.getName() + "': value '" + item + "' - " + e.getMessage());
                                conversionErrorInList = true;
                                break;
                            }
                        } else {
                            convertedList.add(item); // Already converted by Picocli or correct type
                        }
                    }
                    if(conversionErrorInList && paramSchema.isRequired()) return 1;
                    if(!conversionErrorInList) valueFromCli = convertedList; else valueFromCli = null;
                }
            }

            if (valueFromCli != null) {
                paramsMap.put(paramSchema.getName(), valueFromCli);
            } else {
                if (paramSchema.getDefaultTypedValue() != null) {
                    paramsMap.put(paramSchema.getName(), paramSchema.getDefaultTypedValue());
                } else if (paramSchema.isRequired()) {
                    System.err.println("Error: Required parameter '" + paramSchema.getName() + "' for step '" + subcommandName + "' was not provided and has no default value.");
                    CommandLine.Model.CommandSpec subCmdSpecToPrintUsage = spec.subcommands().get(subcommandName).getCommandSpec();
                    if (subCmdSpecToPrintUsage != null) {
                        new CommandLine( subCmdSpecToPrintUsage.userObject()) // Ensure user object is set
                                .usage(System.err);
                    } else {
                        spec.commandLine().usage(System.err);
                    }
                    return 1;
                }
            }
        }

        Data stepParameters = Data.Factory.get().fromMap(paramsMap);
        StepConfig stepConfig = new GenericStepConfig(runnerClassName, stepParameters);

        String outputStr = serializeStepConfig(stepConfig, this.outputFormat);
        if (this.outputFile == null) {
            spec.commandLine().getOut().println(outputStr);
        } else {
            try {
                FileUtils.writeStringToFile(this.outputFile, outputStr, StandardCharsets.UTF_8);
                System.out.println("\nConfiguration for step '" + subcommandName + "' (type: " + runnerClassName +
                        ") written to: " + this.outputFile.getAbsolutePath() + " in " + this.outputFormat + " format.");
            } catch (IOException e) {
                System.err.println("Error writing configuration file '" + this.outputFile.getAbsolutePath() + "': " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }

    // Made static as it doesn't depend on instance state
    private static String serializeStepConfig(StepConfig stepConfig, String format) throws JsonProcessingException {
        if ("yaml".equalsIgnoreCase(format) || "yml".equalsIgnoreCase(format)) {
            return ObjectMappers.getYamlMapper().writerWithDefaultPrettyPrinter().writeValueAsString(stepConfig);
        } else {
            return ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(stepConfig);
        }
    }

    private Integer runInteractiveMode() throws IOException {
        if (this.outputFile == null) {
            System.err.println("Error: Output file (-o, --output) must be specified for interactive mode.");
            spec.commandLine().usage(System.err);
            return 1;
        }

        List<StepSchema> availableSchemas = getAvailableSchemas(); // Uses instance field schemaRegistry
        if (availableSchemas.isEmpty()) {
            return 1;
        }

        System.out.println("\nAvailable step types for creation (Interactive Mode):");
        for (int i = 0; i < availableSchemas.size(); i++) {
            StepSchema schema = availableSchemas.get(i);
            String displayName = schema.getRunnerClassName();
            String description = schema.getDescription() != null && !schema.getDescription().isEmpty() ? schema.getDescription() : "No description available.";
            System.out.printf("%d. %s - %s\n",
                    (i + 1), displayName, description);
        }
        System.out.println("0. Cancel");

        StepSchema selectedSchema = null;
        while (selectedSchema == null) {
            System.out.print("\nSelect step type number to create (or 0 to cancel): ");
            String selectionStr = readLine(); // Uses instance field scanner
            if (selectionStr == null) {
                System.err.println("Input stream closed. Aborting step creation.");
                return 1;
            }
            try {
                int selectionInt = Integer.parseInt(selectionStr.trim());
                if (selectionInt == 0) {
                    System.out.println("Step creation cancelled by user.");
                    return 0;
                }
                int selectionIndex = selectionInt - 1;
                if (selectionIndex >= 0 && selectionIndex < availableSchemas.size()) {
                    selectedSchema = availableSchemas.get(selectionIndex);
                } else {
                    System.err.println("Invalid selection. Please enter a number from the list (1 to " + availableSchemas.size() + ") or 0 to cancel.");
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid input. Please enter a number.");
            }
        }

        System.out.print("Enter a unique name/ID for this step instance (e.g., 'my-python-processor', optional, for your reference): ");
        String stepInstanceNameInput = readLine();
        if (stepInstanceNameInput == null ) {
            System.err.println("Input stream closed. Aborting.");
            return 1;
        }
        final String stepInstanceName = stepInstanceNameInput.trim();

        Map<String, Object> paramsMap = new HashMap<>();
        String schemaDisplayNameForPrompt = selectedSchema.getRunnerClassName();
        System.out.println("\nConfiguring parameters for step instance" +
                (!stepInstanceName.isEmpty() ? " '" + stepInstanceName + "'" : "") +
                " of type '" + schemaDisplayNameForPrompt + "':");
        System.out.println("(Press Enter to skip optional parameters or to use default value if available)");

        List<ParameterSchema> sortedParams = selectedSchema.getParameters().stream()
                .sorted(Comparator.comparing((ParameterSchema p) -> !p.isRequired())
                        .thenComparing(ParameterSchema::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        for (ParameterSchema paramSchema : sortedParams) {
            String typeDisplay = paramSchema.getType().toString();
            if (paramSchema.getType() == ValueType.LIST && paramSchema.getListElementType() != null) {
                typeDisplay += "<" + paramSchema.getListElementType().toString() + ">";
            }
            String paramDescription = paramSchema.getDescription();
            Object paramDefaultRawValue = paramSchema.getDefaultValue();

            String prompt = String.format("Enter value for '%s' (%s)%s%s%s: ",
                    paramSchema.getName(), typeDisplay,
                    paramSchema.isRequired() ? " [required]" : " [optional]",
                    paramDescription != null && !paramDescription.trim().isEmpty() ? " (Description: " + paramDescription + ")" : "",
                    paramDefaultRawValue != null ? " [default: " + paramDefaultRawValue + "]" : ""
            );

            while (true) {
                System.out.print(prompt);
                String valueStr = readLine();
                if (valueStr == null) {
                    System.err.println("Input stream closed. Aborting parameter input for this step.");
                    return 1;
                }

                if (!valueStr.trim().isEmpty()) {
                    try {
                        // Use instance method convertValue
                        Object convertedValue = this.convertValue(valueStr, paramSchema.getType(), paramSchema.getName(), paramSchema.getListElementType());
                        paramsMap.put(paramSchema.getName(), convertedValue);
                        break;
                    } catch (IllegalArgumentException e) {
                        System.err.println("Warning: Could not convert '" + valueStr + "' to " + paramSchema.getType() +
                                " for parameter '" + paramSchema.getName() + "'. Error: " + e.getMessage());
                        if (paramSchema.isRequired() && paramSchema.getDefaultTypedValue() == null) {
                            System.err.println("This is a required parameter with no default. Please try again or fix the input format.");
                        } else {
                            System.out.println(" (You can press Enter to use default if available, or re-enter value)");
                        }
                    }
                } else {
                    if (paramSchema.getDefaultTypedValue() != null) {
                        paramsMap.put(paramSchema.getName(), paramSchema.getDefaultTypedValue());
                        System.out.println(" (Using default: " + paramDefaultRawValue + ")");
                        break;
                    } else if (paramSchema.isRequired()) {
                        System.err.println("Parameter '" + paramSchema.getName() + "' is required and has no default value. Please provide a value.");
                    } else {
                        System.out.println(" (Optional parameter '" + paramSchema.getName() + "' left unset)");
                        break;
                    }
                }
            }
        }

        Data stepParameters = Data.Factory.get().fromMap(paramsMap);
        StepConfig stepConfig = new GenericStepConfig(selectedSchema.getRunnerClassName(), stepParameters);

        try {
            String outputStr = serializeStepConfig(stepConfig, this.outputFormat);
            FileUtils.writeStringToFile(this.outputFile, outputStr, StandardCharsets.UTF_8);
            System.out.println("\nConfiguration for step (type: " + schemaDisplayNameForPrompt +
                    (!stepInstanceName.isEmpty() ? ", instance name: '" + stepInstanceName + "'" : "") +
                    ") written to: " + this.outputFile.getAbsolutePath() + " in " + this.outputFormat + " format.");
        } catch (IOException e) {
            System.err.println("Error writing configuration file '" + this.outputFile.getAbsolutePath() + "': " + e.getMessage());
            return 1;
        }
        return 0;
    }

    // Instance method as it uses instance field scanner
    private String readLine() {
        if (System.console() != null) {
            return System.console().readLine();
        }
        try {
            if (this.scanner.hasNextLine()) {
                return this.scanner.nextLine();
            }
        } catch (IllegalStateException e) {
            System.err.println("Scanner is closed or in invalid state: " + e.getMessage());
        } catch (NoSuchElementException e) {
            System.err.println("No more input available from Scanner: " + e.getMessage());
        }
        return null;
    }

    // Instance method, can be called by both interactive and subcommand handling logic if needed.
    private Object convertValue(String valueStr, ValueType type, String paramName, ValueType listElementType) throws IllegalArgumentException {
        // ... (implementation as provided in your NewStepCreator, kept identical for brevity)
        if (valueStr == null) {
            if (type == ValueType.BOOLEAN) return false;
            return null;
        }
        String trimmedValueStr = valueStr.trim();

        if (type == ValueType.LIST) {
            if (listElementType == null) {
                throw new IllegalStateException("Schema Error: List parameter '" + paramName + "' has no listElementType defined.");
            }
            if (trimmedValueStr.isEmpty()) return Collections.emptyList();
            return Arrays.stream(trimmedValueStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return convertValue(s, listElementType, paramName + "[item]", null);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Error in list item for '" + paramName + "': value '" + s + "' - " + e.getMessage(), e);
                        }
                    })
                    .collect(Collectors.toList());
        }

        switch (type) {
            case STRING: return trimmedValueStr;
            case INT64:
                try { return Long.parseLong(trimmedValueStr); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid INT64 (long) for '" + paramName + "': '" + trimmedValueStr + "'", e); }
            case BOOLEAN:
                if ("true".equalsIgnoreCase(trimmedValueStr)) return true;
                if ("false".equalsIgnoreCase(trimmedValueStr)) return false;
                if ("1".equals(trimmedValueStr)) return true;
                if ("0".equals(trimmedValueStr)) return false;
                throw new IllegalArgumentException("Invalid BOOLEAN for '" + paramName + "': '" + trimmedValueStr + "'. Expected 'true', 'false', '1', or '0'.");
            case DOUBLE:
                try { return Double.parseDouble(trimmedValueStr); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid DOUBLE for '" + paramName + "': '" + trimmedValueStr + "'", e); }
            case DATA:
                if (trimmedValueStr.startsWith("@")) {
                    String filePath = trimmedValueStr.substring(1);
                    try {
                        String jsonContent = FileUtils.readFileToString(new File(filePath), StandardCharsets.UTF_8);
                        return Data.Factory.get().fromJson(jsonContent);
                    } catch (IOException e) {
                        throw new IllegalArgumentException("Could not read DATA from file '" + filePath + "' for parameter '" + paramName + "'. Error: " + e.getMessage(), e);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid JSON content in file '" + filePath + "' for DATA parameter '" + paramName + "'. Error: " + e.getMessage(), e);
                    }
                } else if ((trimmedValueStr.startsWith("{") && trimmedValueStr.endsWith("}")) || (trimmedValueStr.startsWith("[") && trimmedValueStr.endsWith("]"))) {
                    try {
                        return Data.Factory.get().fromJson(trimmedValueStr);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid JSON string for DATA parameter '" + paramName + "': '" + trimmedValueStr + "'. Error: " + e.getMessage(), e);
                    }
                }
                if (trimmedValueStr.isEmpty()) {
                    return Data.Factory.get().empty();
                }
                throw new IllegalArgumentException("DATA parameter '" + paramName + "' expects a valid JSON string or '@filepath', got: '" + trimmedValueStr + "'.");
            case BYTES:
                return trimmedValueStr.getBytes(StandardCharsets.UTF_8);
            case IMAGE:
                case NDARRAY: case POINT: case BOUNDING_BOX:
                return trimmedValueStr;
            default:
                System.err.println("Critical Warning: Unhandled ValueType '" + type + "' during conversion for '" + paramName + "'. Treating as String.");
                return trimmedValueStr;
        }
    }

    /**
     * Static run method similar to the old StepCreator's structure.
     * This allows programmatic invocation if needed, outside of direct Picocli execution of the Callable.
     *
     * @param parseResult            The Picocli ParseResult, expected to be for a specific step subcommand.
     * @param cliOutputFile          The output file, can be null to print to STDOUT.
     * @param cliOutputFormat        The output format ("json" or "yaml").
     * @param newStepCreatorInstance An instance of NewStepCreator, needed for its convertValue method if complex parsing is required.
     * @return Exit code (0 for success, 1 for error).
     * @throws IOException If there's an issue writing the output file.
     */
    public static int run(CommandLine.ParseResult parseResult,
                          File cliOutputFile, String cliOutputFormat,
                          NewStepCreator newStepCreatorInstance) throws IOException {

        CommandLine.ParseResult subcommandParseResult = parseResult;
        // If 'create-step' was called with a subcommand, parseResult here will be the subcommand's ParseResult.
        // If 'create-step' itself was passed (e.g. from a main method that constructed this ParseResult directly
        // without full Picocli dispatch), we might need to get the subcommand.
        // However, the typical usage of such a static run method is after Picocli has fully parsed a specific command.
        if (parseResult.subcommand() != null) {
            subcommandParseResult = parseResult.subcommand();
        } else if (!parseResult.isUsageHelpRequested() && !parseResult.isVersionHelpRequested()) {
            // This implies 'create-step' itself was invoked without a specific step type subcommand from this static context.
            // This static 'run' method is designed to handle a specific, parsed step type.
            // For interactive mode, the instance 'call()' method should be used via Picocli dispatch.
            System.err.println("Error: This static 'run' method expects a ParseResult for a specific step subcommand.");
            new CommandLine(new NewStepCreator()).usage(System.err); // Show main usage
            return 1;
        }


        String subcommandName = subcommandParseResult.commandSpec().name();

        // To find the runnerClassName, iterate available schemas.
        // generateSubcommandName and getAvailableSchemas need to be accessible or logic replicated.
        // For simplicity here, we assume NewStepCreator instance methods are available via newStepCreatorInstance.
        Optional<StepSchema> selectedSchemaOpt = newStepCreatorInstance.getAvailableSchemas().stream()
                .filter(s -> newStepCreatorInstance.generateSubcommandName(s.getRunnerClassName()).equals(subcommandName))
                .findFirst();

        if (!selectedSchemaOpt.isPresent()) {
            System.err.println("Error: Could not find schema for step type '" + subcommandName + "'.");
            return 1;
        }
        StepSchema selectedSchema = selectedSchemaOpt.get();
        String runnerClassName = selectedSchema.getRunnerClassName();

        Map<String, Object> paramsMap = new HashMap<>();
        for (ParameterSchema paramSchema : selectedSchema.getParameters()) {
            String paramCliName = "--" + paramSchema.getName();
            Object valueFromCli = null;

            if (subcommandParseResult.hasMatchedOption(paramCliName)) {
                valueFromCli = subcommandParseResult.matchedOptionValue(paramCliName, null);
                if (valueFromCli instanceof String) {
                    ValueType expectedType = paramSchema.getType();
                    if (expectedType == ValueType.DATA || expectedType == ValueType.BYTES ||
                            (expectedType == ValueType.LIST && (paramSchema.getListElementType() == ValueType.DATA || paramSchema.getListElementType() == ValueType.BYTES )) ||
                            expectedType == ValueType.IMAGE || expectedType == ValueType.NDARRAY || expectedType == ValueType.POINT || expectedType == ValueType.BOUNDING_BOX ) {
                        try {
                            valueFromCli = newStepCreatorInstance.convertValue((String) valueFromCli, expectedType, paramSchema.getName(), paramSchema.getListElementType());
                        } catch (IllegalArgumentException e) {
                            System.err.println("Error parsing CLI value for parameter '" + paramSchema.getName() + "': " + e.getMessage());
                            if(paramSchema.isRequired()) return 1;
                            valueFromCli = null;
                        }
                    }
                } else if (paramSchema.getType() == ValueType.LIST && valueFromCli instanceof List) {
                    // Similar list conversion logic as in handleSubcommandCall
                    List<?> rawList = (List<?>) valueFromCli;
                    List<Object> convertedList = new ArrayList<>();
                    boolean conversionErrorInList = false;
                    for (Object item : rawList) {
                        if (item instanceof String) {
                            try {
                                convertedList.add(newStepCreatorInstance.convertValue((String) item, paramSchema.getListElementType(), paramSchema.getName() + "[item]", null));
                            } catch (IllegalArgumentException e) {
                                System.err.println("Error in list item for '" + paramSchema.getName() + "': value '" + item + "' - " + e.getMessage());
                                conversionErrorInList = true; break;
                            }
                        } else { convertedList.add(item); }
                    }
                    if(conversionErrorInList && paramSchema.isRequired()) return 1;
                    if(!conversionErrorInList) valueFromCli = convertedList; else valueFromCli = null;
                }
            }

            if (valueFromCli != null) {
                paramsMap.put(paramSchema.getName(), valueFromCli);
            } else {
                if (paramSchema.getDefaultTypedValue() != null) {
                    paramsMap.put(paramSchema.getName(), paramSchema.getDefaultTypedValue());
                } else if (paramSchema.isRequired()) {
                    System.err.println("Error: Required parameter '" + paramSchema.getName() + "' for step '" + subcommandName + "' was not provided and has no default value.");
                    new CommandLine(subcommandParseResult.commandSpec() != null ? subcommandParseResult.commandSpec().userObject() : newStepCreatorInstance)
                            .usage(System.err);
                    return 1;
                }
            }
        }

        Data stepParameters = Data.Factory.get().fromMap(paramsMap);
        StepConfig stepConfig = new GenericStepConfig(runnerClassName, stepParameters);

        String outputStr = serializeStepConfig(stepConfig, cliOutputFormat); // Use static serialize
        if (cliOutputFile == null) {
            // Get PrintWiter from ParseResult's command spec or main command spec
            PrintWriter outWriter = subcommandParseResult.commandSpec().commandLine().getOut();
            outWriter.println(outputStr);
        } else {
            try {
                FileUtils.writeStringToFile(cliOutputFile, outputStr, StandardCharsets.UTF_8);
                System.out.println("\nConfiguration for step '" + subcommandName + "' (type: " + runnerClassName +
                        ") written to: " + cliOutputFile.getAbsolutePath() + " in " + cliOutputFormat + " format.");
            } catch (IOException e) {
                System.err.println("Error writing configuration file '" + cliOutputFile.getAbsolutePath() + "': " + e.getMessage());
                return 1;
            }
        }
        return 0;
    }
}