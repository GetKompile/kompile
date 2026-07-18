package ai.kompile.chat.local.cli;

import ai.kompile.chat.local.*;
import ai.kompile.chat.local.sdx.SdxChatModel;
import ai.kompile.chat.local.sdx.SdxLlmAbi;
import ai.kompile.chat.local.sdx.SdxSubprocessChatModel;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Local-first chat REPL. Loads a .kgraph + optional SDX model, tool-calls graph reasoning,
 * falls back to remote kompile chat endpoint when local inference is unavailable.
 *
 * Usage: java -jar kompile-chat-local-cli.jar [options]
 * Options:
 *   --kgraph <path>        Path to standalone .kgraph file
 *   --project <path>       Project directory or .kproject archive
 *   --fact-sheet-id <id>  Select data/graph/factsheet-<id>.kgraph from a project
 *   --model <path>         Path to SDX model file (.gguf/.sdz)
 *   --tokenizer <path>     Path to tokenizer.json or directory containing it
 *   --sdx-bin <path>       Absolute path to sdx-llm binary (subprocess mode)
 *   --sdx-lib <path>       Absolute path to libsdx_llm.so (used for in-process JNA or auto-probe)
 *   --sdx-mode <mode>      SDX inference mode: auto (default), inprocess, subprocess
 *   --remote-url <url>     Remote base URL (e.g. http://localhost:8091)
 *   --remote-model <name>  Remote model name (default: gpt-4o-mini)
 *   --remote-key <key>     Remote API key
 *   --config <path>        Config properties file
 *   --temperature <float>  Sampling temperature (default: 0.7)
 *   --max-tool-rounds <n>  Max tool rounds per turn (default: 4)
 *
 * REPL commands:
 *   /save <path>    Save current graph state to .kgraph
 *   /tools          List available graph tools
 *   /quit           Exit
 */
public class ChatCli {

    private static final Logger log = LoggerFactory.getLogger(ChatCli.class);

    // Injection seam for tests: set this to override the model factory
    static ChatModel testModelOverride = null;

    public static void main(String[] args) throws Exception {
        // Parse args
        Path kgraphPath = null;
        Path projectPath = null;
        String factSheetId = null;
        boolean kgraphSpecified = false;
        boolean projectSpecified = false;
        String modelPath = null;
        String tokenizerPath = null;
        String sdxLib = null;
        String sdxBin = null;
        String sdxMode = null;
        String remoteUrl = null;
        String remoteModel = "gpt-4o-mini";
        String remoteKey = null;
        Path configPath = null;
        double temperature = 0.7;
        int maxToolRounds = 4;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--kgraph" -> {
                    kgraphPath = Path.of(args[++i]);
                    kgraphSpecified = true;
                }
                case "--project" -> {
                    projectPath = Path.of(args[++i]);
                    projectSpecified = true;
                }
                case "--fact-sheet-id" -> factSheetId = args[++i];
                case "--model" -> modelPath = args[++i];
                case "--tokenizer" -> tokenizerPath = args[++i];
                case "--sdx-bin" -> sdxBin = args[++i];
                case "--sdx-lib" -> sdxLib = args[++i];
                case "--sdx-mode" -> sdxMode = args[++i];
                case "--remote-url" -> remoteUrl = args[++i];
                case "--remote-model" -> remoteModel = args[++i];
                case "--remote-key" -> remoteKey = args[++i];
                case "--config" -> configPath = Path.of(args[++i]);
                case "--temperature" -> temperature = Double.parseDouble(args[++i]);
                case "--max-tool-rounds" -> maxToolRounds = Integer.parseInt(args[++i]);
                default -> {
                    if (!args[i].startsWith("--")) {
                        System.err.println("Unknown argument: " + args[i]);
                    }
                }
            }
        }

        // Load config file if specified (args override config)
        ChatConfig config = (configPath != null) ? ChatConfig.fromFile(configPath) : ChatConfig.defaults();

        // Args override config. An explicit graph-source flag suppresses both configured sources.
        AssetSelection assets = resolveAssetSelection(projectPath, projectSpecified, kgraphPath,
                kgraphSpecified, factSheetId, config);
        projectPath = assets.projectPath();
        kgraphPath = assets.kgraphPath();
        factSheetId = assets.factSheetId();
        if (modelPath == null && config.modelPath().isPresent()) modelPath = config.modelPath().get();
        if (tokenizerPath == null) tokenizerPath = config.tokenizerPath().orElse(null);
        if (sdxLib == null) sdxLib = config.sdxLibPath().orElse(null);
        if (sdxBin == null) sdxBin = config.sdxBinPath().orElse(null);
        if (sdxMode == null) sdxMode = config.sdxMode();
        if (remoteUrl == null && config.remoteBaseUrl().isPresent()) remoteUrl = config.remoteBaseUrl().get();
        if (remoteKey == null && config.remoteApiKey().isPresent()) remoteKey = config.remoteApiKey().get();

        // Configure SDX native lib path BEFORE any JNA class is touched.
        // SdxLlmAbi.configureLibPath sets jna.library.path + kompile.chat.sdx.lib
        // so the static Loader (which runs on first SdxLlmAbi reference) finds the file.
        if (sdxLib != null) {
            SdxLlmAbi.configureLibPath(sdxLib);
            System.out.println("[sdx] lib path: " + sdxLib);
        }

        // Build graph bridge. Keep an archive resolver alive until the bridge is closed.
        ProjectBundleResolver.ResolvedProject resolvedProject = null;
        GraphToolBridge bridge = null;
        try {
            if (projectPath != null) {
                resolvedProject = ProjectBundleResolver.resolve(projectPath, factSheetId);
                bridge = GraphToolBridge.open(resolvedProject.graphPath());
                System.out.println("[graph] Loaded project " + resolvedProject.projectName()
                        + " (" + resolvedProject.projectId() + "): " + resolvedProject.graphPath());
            } else if (kgraphPath != null) {
                if (!Files.isRegularFile(kgraphPath)) {
                    throw new IOException("Requested .kgraph file does not exist: " + kgraphPath);
                }
                bridge = GraphToolBridge.open(kgraphPath);
                System.out.println("[graph] Loaded: " + kgraphPath);
            } else {
                bridge = GraphToolBridge.empty();
                System.out.println("[graph] No project or .kgraph specified, starting empty.");
            }

        // Print graph stats (use graph_reasoning_query for an overview)
        try {
            String overview = bridge.execute("graph_reasoning_query", "{\"query\":\"list all entities\",\"queryType\":\"ENTITY_SEARCH\"}");
            System.out.println("[graph] " + overview);
        } catch (Exception e) {
            // non-fatal — empty graph or tool unavailable
        }

        // Build inference models
        //
        // Mode selection (KOMPILE_CHAT_SDX_MODE / --sdx-mode):
        //   auto       (default) probe the native lib; use in-process JNA when the export-allowlist
        //              fix is in place (libsdx_llm.so built with SDX_LLM_1 version script), else
        //              fall back to subprocess.
        //   inprocess  always use JNA Native.load (requires fixed libsdx_llm.so).
        //   subprocess always fork the sdx-llm CLI binary per generate call.
        //
        // The export-allowlist fix (FIX 1) hides graal_*/JNI_*/__svm_* from .dynsym so that
        // RTLD_LOCAL loading of libsdx_llm.so into a JVM process no longer causes
        // ExceptionInInitializerError from GraalVM isolate symbol collisions.
        final String resolvedMode = (sdxMode != null) ? sdxMode.toLowerCase().trim() : "auto";
        ChatModel localModel = null;
        if (testModelOverride != null) {
            localModel = testModelOverride;
        } else if (modelPath != null) {
            boolean useInProcess = switch (resolvedMode) {
                case "inprocess" -> true;
                case "subprocess" -> false;
                default -> {
                    // auto: probe the lib — if sdxLib is specified and passes IS_AVAILABLE, go in-process.
                    if (sdxLib != null) {
                        yield probeInProcess(sdxLib);
                    } else if (sdxBin != null) {
                        // bin is configured without lib → subprocess
                        yield false;
                    } else {
                        // Neither lib nor bin: try JNA default search path probe.
                        yield SdxLlmAbi.IS_AVAILABLE;
                    }
                }
            };

            if (useInProcess) {
                localModel = new SdxChatModel(modelPath, tokenizerPath);
                System.out.println("[sdx] mode=in-process (JNA), lib=" + (sdxLib != null ? sdxLib : "default-search"));
            } else {
                if (sdxBin == null) {
                    System.err.println("[sdx] WARNING: subprocess mode requested but --sdx-bin not set; local inference disabled.");
                } else {
                    localModel = new SdxSubprocessChatModel(sdxBin, modelPath, tokenizerPath);
                    System.out.println("[sdx] mode=subprocess, bin: " + sdxBin);
                }
            }
        }

        ChatModel remoteModel2 = null;
        if (remoteUrl != null) {
            remoteModel2 = new RemoteChatModel(remoteUrl, remoteModel, remoteKey, 60);
        }

        InferenceRouter router = new InferenceRouter(localModel, remoteModel2);

        // Print active route
        System.out.println("[inference] Active route: " + router.activeRoute());
        if (router.activeRoute().equals("NONE")) {
            System.out.println("[inference] WARNING: No inference backend available. Use --remote-url or --model.");
        }
        if (localModel instanceof SdxSubprocessChatModel spModel) {
            System.out.println("[inference] Local model: " + spModel.modelId()
                    + " (template=" + spModel.chatTemplate() + ", mode=subprocess)");
        } else if (localModel instanceof SdxChatModel sdxModel) {
            System.out.println("[inference] Local model: " + sdxModel.modelId()
                    + " (template=" + sdxModel.chatTemplate() + ", mode=in-process)");
        }

        // REPL
        List<Message> history = new ArrayList<>();
        ChatEngine engine = new ChatEngine(router, bridge, maxToolRounds);
        GenOptions opts = new GenOptions.Builder().temperature(temperature).build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("\nType your message, /tools, /save <path>, or /quit\n");

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.equals("/quit")) {
                System.out.println("Bye.");
                break;
            } else if (line.equals("/tools")) {
                System.out.println("[tools] " + bridge.catalogJson());
                continue;
            } else if (line.startsWith("/save ")) {
                String savePath = line.substring(6).trim();
                try {
                    bridge.session().save(Path.of(savePath));
                    System.out.println("[graph] Saved to " + savePath);
                } catch (Exception e) {
                    System.out.println("[graph] Save failed: " + e.getMessage());
                }
                continue;
            }

            try {
                ChatEngine.TurnResult result = engine.chat(history, line, opts);
                System.out.println();
                // Print tool rounds
                for (ChatEngine.ToolRound round : result.rounds()) {
                    System.out.println("[tool:" + round.tool() + "] " + round.resultJson());
                }
                // Print answer
                System.out.println("Assistant: " + result.answer());
                System.out.println();

                // Update history
                history.add(Message.user(line));
                history.add(Message.assistant(result.answer()));
            } catch (ChatException e) {
                System.out.println("[error] " + e.getMessage());
            }
        }

        } finally {
            if (bridge != null) {
                bridge.close();
            }
            if (resolvedProject != null) {
                resolvedProject.close();
            }
        }
    }

    static AssetSelection resolveAssetSelection(Path cliProject, boolean projectSpecified,
                                                 Path cliKgraph, boolean kgraphSpecified,
                                                 String cliFactSheetId, ChatConfig config) {
        if (projectSpecified && kgraphSpecified) {
            throw new IllegalArgumentException("--project and --kgraph cannot be used together");
        }
        Path project = cliProject;
        Path kgraph = cliKgraph;
        if (!projectSpecified && !kgraphSpecified) {
            project = config.projectPath().orElse(null);
            kgraph = config.kgraphPath().orElse(null);
        }
        if (project != null && kgraph != null) {
            throw new IllegalArgumentException("Project and kgraph paths cannot both be configured");
        }
        String factSheet = cliFactSheetId != null
                ? cliFactSheetId : config.factSheetId().orElse(null);
        if (factSheet != null && project == null) {
            throw new IllegalArgumentException("--fact-sheet-id requires --project (or project.path)");
        }
        return new AssetSelection(project, kgraph, factSheet);
    }

    record AssetSelection(Path projectPath, Path kgraphPath, String factSheetId) {}

    /**
     * Probe whether the native {@code libsdx_llm} can be loaded in-process without crashing.
     *
     * <p>Sets up the JNA library path from {@code libPath}, triggers the {@link SdxLlmAbi}
     * static loader, then calls {@code sdxLlmCreateRuntime}/{@code sdxLlmAbiVersion}/
     * {@code sdxLlmDestroyRuntime} to confirm the isolate lifecycle works.  Returns
     * {@code false} on any {@link Throwable} so callers fall back to subprocess mode.</p>
     *
     * <p>This probe is only meaningful when {@code libsdx_llm.so} was built with the
     * export-allowlist version script ({@code SDX_LLM_1 { global: sdx*; local: *; }}) —
     * without it, the RTLD_LOCAL load still conflicts with the JVM's GraalVM runtime.</p>
     *
     * @param libPath absolute path to {@code libsdx_llm.so}
     * @return {@code true} if in-process mode is safe
     */
    static boolean probeInProcess(String libPath) {
        try {
            SdxLlmAbi.configureLibPath(libPath);
            if (!SdxLlmAbi.IS_AVAILABLE) {
                log.warn("[sdx] probe: library not loadable at {}", libPath);
                return false;
            }
            // Create a runtime, verify it responds, tear down immediately.
            Pointer rt = SdxLlmAbi.INSTANCE.sdxLlmCreateRuntime();
            if (rt == null) {
                log.warn("[sdx] probe: sdxLlmCreateRuntime returned null — in-process mode unavailable");
                return false;
            }
            int ver = SdxLlmAbi.INSTANCE.sdxLlmAbiVersion(rt);
            SdxLlmAbi.INSTANCE.sdxLlmDestroyRuntime(rt);
            log.info("[sdx] probe: in-process OK (ABI version={})", ver);
            return true;
        } catch (Throwable t) {
            log.warn("[sdx] probe: in-process load failed ({}), falling back to subprocess", t.getMessage());
            return false;
        }
    }
}
