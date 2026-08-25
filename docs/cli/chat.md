# Chat

Kompile supports first-party local model chat, external/direct model chat, an installed chat subprocess, server-connected RAG, and agent passthrough.

## Setup wizard and standard chat

Bare chat preserves the normal interactive setup flow:

```bash
kompile chat
```

The wizard offers Standard Chat, Passthrough, and Resume. Standard Chat then asks which runtime to use:

1. **Kompile local model** — starts the distribution's first-party low-overhead serving subprocess against a local `.gguf` or `.sdz` model. It does not start or connect to a full Kompile instance.
2. **External local endpoint** — connects to Ollama or another OpenAI-compatible endpoint without a Kompile instance.
3. **Direct model provider** — calls a cloud model API directly without a Kompile instance.
4. **Kompile instance** — connects to a configured instance or starts the installed `kompile-chat` service for a local URL.

The first option is the native Kompile path and uses the same low-overhead serving component as the higher-level application: `ServingSubprocessMain`. The CLI writes the standard `ServingSubprocessArgs` JSON and launches one of the installed forms:

```text
<kompile-app-native> --subprocess=serving <args.json>
<java> -jar <kompile-app-main-fat.jar> --subprocess=serving <args.json>
```

The child preloads the selected model and exposes Kompile's bounded LLM API only on a private loopback port. Standard Chat waits for `/api/llm/status`, uses `/api/llm/chat` for canonical structured messages, tool definitions, and tool calls, and stops the owned child when the REPL exits. This is not the full Kompile application instance.

The CLI prefers the installed app-native executable and falls back to the installed `kompile-app-main` fat JAR plus Java. Set `KOMPILE_CHAT_SERVING_EXECUTABLE` or `KOMPILE_CHAT_SERVING_JAR` to override runtime discovery. Set `KOMPILE_CHAT_MODEL_PATH` or `KOMPILE_CHAT_TOKENIZER_PATH` to override model discovery. Supported cached models are discovered under `~/.kompile/models/chat` and the DL4J model cache; matching first-party tokenizer assets are resolved from `~/.kompile/models/tokenizers` and passed explicitly to the serving subprocess.

The external-local option is deliberately separate. Choose Ollama to accept its default URL, or choose OpenAI-compatible to enter another endpoint such as one started by `kompile run ... --serve`.

The direct-provider path lists each vendor once. After selecting a vendor, the wizard asks for OAuth/subscription sign-in or an API key only when both are supported; OAuth-only and API-key-only vendors proceed with their single available method. Internal provider routes such as OpenAI's ChatGPT OAuth endpoint are not shown as duplicate vendors.

Inside an active external/direct standard-chat session, run `/provider` (or `/setup`) to choose a different vendor, authentication method, model, and thinking effort. The switch applies to subsequent messages without changing the session ID or transcript; provider-specific wire envelopes are rebuilt from the same canonical text conversation so the new provider retains context. Selecting the first-party local subprocess or a Kompile instance is saved for the next session because either changes the underlying runtime.

For direct providers and models that expose reasoning effort, the wizard also offers a Thinking Effort choice after model selection. Choose the provider/model default or an explicit value from `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, and `max`; the choice is saved with the standard-chat configuration and sent on direct model requests. Not every reasoning model supports every explicit value.

At the standard chat prompt, Ctrl-C exits through the normal session cleanup path. Use the configured cancel key (Escape by default) to cancel an in-progress model operation.

### Prompt reminders

Use reminders when an instruction must be repeated at the front of every prompt sent by Kompile Chat. Session reminders are the default and follow the conversation when it is resumed:

```text
/reminder Always run the focused tests before answering.
/reminder
/reminder clear
```

Project-global reminders apply to every chat session started in the same project folder and are stored in `.kompile/chat-reminders.json`:

```text
/reminder-global Do not edit generated files.
/reminder-global list
/reminder-global clear
```

`/reminder add <text>` and `/reminder-global add <text>` are explicit aliases for adding reminders. Project-global reminders are prepended first, followed by session reminders. The injected reminder block is sent to the model but is not written into the user transcript or used as the session title.

If Standard Chat selects the local `Kompile` provider and no process is listening at its local chat URL, the CLI starts only `kompile-chat` from the installed distribution and connects the REPL to it. Startup happens after the wizard has resolved the mode and provider; it never replaces or bypasses model configuration.

The native executable is preferred, with `kompile-chat.jar` as fallback, through the normal Kompile process manager. When `kompile` itself is a native image, it infers the distribution root from its own executable and directly launches the sibling `bin/kompile-chat` binary. It uses `~/.kompile` as its data directory, writes subprocess output under `~/.kompile/logs`, and does not create a project or start app-main, model staging, crawling, or model-serving infrastructure.

Explicit `--url`, `--port`, `--local`, and passthrough routes retain their existing behavior and never trigger subprocess startup implicitly. A configured remote Kompile URL is connect-only.

```bash
# Connect to the default chat URL without launching the installed subprocess
kompile chat --no-start

# Allow longer for the chat application to initialize
kompile chat --startup-timeout 300
```

Run the wizard explicitly at any time with `kompile chat --setup`.

## Local and direct LLM chat

A saved first-party local, external-local, or direct-provider configuration is used automatically; no full Kompile instance is needed. First-party local chat owns Kompile's serving subprocess for the session, while Ollama and OpenAI-compatible endpoints remain optional external routes.

```bash
kompile chat --setup
kompile chat --local
```

## Server-connected RAG chat

An explicit URL is connect-only and does not start local services. The chat persona uses port 8081 by default.

```bash
kompile chat --url=http://localhost:8081
```

Queries flow through optional query rewriting, embedding, vector search, reranking, filters, and LLM generation with retrieved context.

## Agent passthrough

Wraps an AI agent (Claude Code, Codex, etc.) with Kompile's MCP tools for RAG search, graph RAG, file I/O, code search, and memory.

```bash
kompile chat --mode passthrough --agent=claude-code --rag --role=architect
```

Kompile injects its MCP tools into the agent, adds a system prompt, and manages session persistence.

### Project-local knowledge runtime

A passthrough agent can crawl and search the current project without a Kompile server. Start the
stdio MCP server with the project as its work directory, call `crawl_discover` to inspect executable
loaders, chunkers, and pipeline templates, then use `crawl_documents` or `crawl_source`. The agent
host performs loading, pooled stdio pipeline execution, chunking, and indexing into
`data/crawls/<knowledge-base>`; `knowledge_search`, `knowledge_status`, and `memory` remain available
in the same session.

The local runtime supports standard text, code, tables, keyword-only indexes, OCR, VLM, and custom
unified pipelines. Every model-backed step uses the same packaged runtime contract. Connecting with
`--url` keeps the same MCP contracts but hands job scheduling, shared state, and distributed model
coordination to the full server.

```bash
# Resume a previous session
kompile chat --continue

# List sessions
kompile session list
```

## Running a local LLM

```bash
# Downloads from HuggingFace, starts an OpenAI-compatible server
kompile run Qwen/Qwen3-0.6B --serve --port=8000

# Interactive chat with a local model
kompile run Qwen/Qwen3-0.6B --backend=cuda
```
