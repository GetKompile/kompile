package ai.kompile.pipelines.steps.documentparser;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ToHTMLContentHandler; // For layout/HTML
import org.xml.sax.SAXException;
// For tables, Tika's direct extraction is limited. Often requires parsing HTML output.
// import org.apache.tika.sax.TableContentHandler;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;


public class DocumentParserRunner implements PipelineStepRunner {

    private String inputKey;
    private String outputKeyPrefix;
    private List<TextExtractionType> extractionTypes;
    private String password; // For encrypted documents

    private transient Parser tikaParser; // transient as TikaConfig/Parser might not be fully serializable or lightweight
    private transient ParseContext parseContext;

    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        StepSchema schema = SchemaRegistry.getInstance().getSchema(stepConfig.runnerClassName())
                .orElseThrow(() -> new IllegalStateException(
                        "No schema found for runner: " + stepConfig.runnerClassName()));

        ConfigAccessor config = new ConfigAccessor(stepConfig.getParameters(), schema);

        this.inputKey = config.getString(DocumentParserConstants.PARAM_INPUT_KEY, DocumentParserConstants.DEFAULT_INPUT_KEY);
        this.outputKeyPrefix = config.getString(DocumentParserConstants.PARAM_OUTPUT_KEY_PREFIX, DocumentParserConstants.DEFAULT_OUTPUT_KEY_PREFIX);
        this.password = config.getString(DocumentParserConstants.PARAM_PASSWORD, null);

        List<String> extractionTypeStrings = config.getStringList(DocumentParserConstants.PARAM_EXTRACTION_TYPES,
                Arrays.asList(TextExtractionType.PLAIN_TEXT.name(), TextExtractionType.METADATA.name()));

        this.extractionTypes = extractionTypeStrings.stream()
                .map(s -> {
                    try {
                        return TextExtractionType.valueOf(s.trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (this.extractionTypes.isEmpty()) {
            this.extractionTypes.addAll(Arrays.asList(TextExtractionType.PLAIN_TEXT, TextExtractionType.METADATA));
        }

        String tikaConfigPath = config.getString(DocumentParserConstants.PARAM_TIKA_CONFIG_PATH, null);
        TikaConfig tikaConfig;
        if (tikaConfigPath != null && !tikaConfigPath.isEmpty()) {
            try (InputStream tikaConfigStream = Files.newInputStream(Paths.get(tikaConfigPath))) {
                tikaConfig = new TikaConfig(tikaConfigStream);
            } catch (Exception e) {
                tikaConfig = TikaConfig.getDefaultConfig();
            }
        } else {
            tikaConfig = TikaConfig.getDefaultConfig();
        }

        this.tikaParser = new AutoDetectParser(tikaConfig);
        this.parseContext = new ParseContext();
        this.parseContext.set(Parser.class, this.tikaParser); // Important for AutoDetectParser
        if (this.password != null) {
            this.parseContext.set(org.apache.tika.parser.PasswordProvider.class, (metadata) -> password);
        }

        this.initialized = true;
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("DocumentParserRunner not initialized. Call init() first.");
        }
        Objects.requireNonNull(input, "Input Data object cannot be null.");

        byte[] documentBytes = input.getBytes(inputKey);
        if (documentBytes == null) {
            return Data.empty(); // Or input.dup() if pass-through is desired
        }

        Data output = Data.empty();
        Metadata tikaMetadata = new Metadata(); // Collect metadata here

        for (TextExtractionType type : extractionTypes) {
            // Tika parser needs a fresh stream for each parse operation with different handlers
            try (InputStream stream = new ByteArrayInputStream(documentBytes)) {
                switch (type) {
                    case PLAIN_TEXT:
                        BodyContentHandler textHandler = new BodyContentHandler(-1); // -1 for unlimited
                        tikaParser.parse(stream, textHandler, tikaMetadata, parseContext);
                        output.put(outputKeyPrefix + ".text", textHandler.toString());
                        break;
                    case HTML:
                    case LAYOUT_TEXT: // Treat LAYOUT_TEXT as HTML as Tika's direct layout is tricky
                        BodyContentHandler underlyingHtmlHandler = new BodyContentHandler(-1);
                        ToHTMLContentHandler toHtmlHandler = new ToHTMLContentHandler();
                        tikaParser.parse(stream, toHtmlHandler, tikaMetadata, parseContext);
                        output.put(outputKeyPrefix + (type == TextExtractionType.HTML ? ".html" : ".layoutText"), underlyingHtmlHandler.toString());
                        break;
                    case METADATA:
                        // Ensure metadata is populated if not already by another extraction
                        if (tikaMetadata.names().length == 0) {
                            tikaParser.parse(stream, new BodyContentHandler(-1), tikaMetadata, parseContext); // Dummy parse to get metadata
                        }
                        Data metadataMap = Data.empty();
                        for (String name : tikaMetadata.names()) {
                            if (tikaMetadata.isMultiValued(name)) {
                                metadataMap.putList(name, Arrays.asList(tikaMetadata.getValues(name)), ValueType.STRING);
                            } else {
                                metadataMap.put(name, tikaMetadata.get(name));
                            }
                        }
                        // Common properties
                        if (tikaMetadata.get(TikaCoreProperties.TITLE) != null) metadataMap.putIfAbsent("title", tikaMetadata.get(TikaCoreProperties.TITLE));
                        if (tikaMetadata.get(TikaCoreProperties.CREATOR) != null) metadataMap.putIfAbsent("author", tikaMetadata.get(TikaCoreProperties.CREATOR));
                        if (tikaMetadata.get(TikaCoreProperties.CREATED) != null) metadataMap.putIfAbsent("createdDate", tikaMetadata.get(TikaCoreProperties.CREATED).toString()); // Convert Date to String
                        if (tikaMetadata.get(TikaCoreProperties.MODIFIED) != null) metadataMap.putIfAbsent("modifiedDate", tikaMetadata.get(TikaCoreProperties.MODIFIED).toString());

                        output.put(outputKeyPrefix + ".metadata", metadataMap);
                        break;
                    case TABLES:
                        // Tika's direct table extraction via TableContentHandler is very basic and often not what users expect.
                        // A more common approach is to parse the HTML output for <table> structures.
                        // For this example, we'll indicate that more advanced table extraction would require
                        // either a dedicated library or parsing the HTML output.
                        // Attempt to get HTML if not already present, then one could parse it.
                        // For now, just putting a placeholder or a very basic attempt if possible.
                        output.put(outputKeyPrefix + ".tablesInfo", "Rudimentary table extraction. Parse HTML output for better results.");
                        break;
                }
            } catch (IOException | TikaException | SAXException e) { // SAXException from ContentHandler
                String errorKey = outputKeyPrefix + "." + type.name().toLowerCase() + ".error";
                String errorMessage = String.format("Error during document parsing for type %s: %s", type, e.getMessage());
                output.put(errorKey, errorMessage);
            }
        }
        return output;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        // TikaConfig and AutoDetectParser generally do not require explicit closing
        // unless custom components within them hold releasable resources.
        this.tikaParser = null;
        this.parseContext = null;
        this.initialized = false;
    }
}