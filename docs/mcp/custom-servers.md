# Custom MCP servers

Kompile chat can connect to third-party MCP servers and expose their tools to the model. The CLI owns one configuration lifecycle for standard chat, headless chat, and managed passthrough:

```bash
kompile mcp add <name> -- <command> [args...]
kompile mcp list
kompile mcp get <name>
kompile mcp disable <name>
kompile mcp enable <name>
kompile mcp remove <name>
```

`add` also has the alias `install`, and `remove` has the alias `uninstall`. Installing a stdio server records its launcher. Kompile starts that launcher when a new chat session loads the server; package runners such as `npx`, `uvx`, or Docker perform their normal package installation/pull at that time. Kompile does not maintain a separate MCP package registry.

Restart an active chat or passthrough session after changing MCP configuration.

## Scopes and precedence

| Scope | File | Behavior |
|---|---|---|
| Project (default) | `<project>/.mcp.json` | Portable with the project; overrides a user server with the same name |
| User | `~/.kompile/config/mcp-servers.json` | Available in every project |
| Effective | user merged with project | Used by chat; shown by `kompile mcp list` |

Choose a scope with `--scope project` or `--scope user`. `list` and `get` accept `--scope effective|project|user`.

Project `.mcp.json` files can execute commands. Interactive standard chat asks before loading an untrusted project config; when no interactive console is available it safely skips that project config instead of prompting. User-scope entries are treated as explicitly installed by the user. Only run headless or passthrough sessions in repositories you trust, and keep third-party server permissions narrow.

The names `kompile`, `kompile-app`, and `kompile-model-staging` are reserved for Kompile's own MCP integration. The custom-server loader ignores those entries, preventing a nested `mcp-stdio` process from recursively loading itself.

## Install examples

### Local stdio server

```bash
kompile mcp add context7 -- npx -y @upstash/context7-mcp
kompile mcp add --scope user python-tools -- uvx my-python-mcp
```

Pass explicit environment entries with `--env`. Prefer environment references instead of literal secrets:

```bash
kompile mcp add github \
  --env GITHUB_PERSONAL_ACCESS_TOKEN='$GITHUB_PERSONAL_ACCESS_TOKEN' \
  -- docker run -i --rm -e GITHUB_PERSONAL_ACCESS_TOKEN ghcr.io/github/github-mcp-server
```

Use `--cwd PATH` for a stdio process working directory and `--timeout-seconds N` for initialization and tool calls. Relative `cwd` values resolve from the project folder.

Kompile removes ambient environment variables whose names look credential-bearing (`TOKEN`, `SECRET`, `PASSWORD`, `API_KEY`, `PRIVATE_KEY`, `ACCESS_KEY`, `AUTH`, or `CREDENTIAL`) before starting an arbitrary stdio server. A sensitive variable is forwarded only when that server explicitly names it under `--env`.

### Streamable HTTP server

Streamable HTTP is the recommended remote transport:

```bash
kompile mcp add --transport http context7 https://mcp.context7.com/mcp
kompile mcp add --scope user --transport http internal https://mcp.example.com/mcp \
  --header-env X-Api-Key=INTERNAL_MCP_KEY \
  --bearer-token-env-var INTERNAL_MCP_BEARER
```

`--header NAME=VALUE` stores a static header. Prefer `--header-env NAME=ENV_VAR` or `--bearer-token-env-var ENV_VAR` for credentials. `list` and `get` redact configured environment and header values.

### Legacy SSE server

SSE is retained for older servers:

```bash
kompile mcp add --transport sse legacy https://mcp.example.com/sse
```

Custom headers are intentionally unsupported on the legacy SSE client. Use Streamable HTTP when authentication or headers are required.

## Tool control and failure policy

Restrict a large or sensitive server before giving it to a model:

```bash
kompile mcp add safe-db \
  --include-tool schema,query \
  --exclude-tool execute_ddl \
  --required \
  -- db-mcp --read-only
```

- `--include-tool`: allowlist tools exposed by the server.
- `--exclude-tool`: denylist applied after the allowlist.
- `--required`: make MCP tool initialization fail when the server is unavailable.
- `--disabled`: record without connecting; use `kompile mcp enable` later.
- `--replace`: update an existing entry in the selected scope.

## How models use custom tools

Standard and headless Kompile chat register custom tools under namespaced IDs:

```text
mcp__<server-name>__<tool-name>
```

They also expose two stable gateway tools:

- `mcp_tool_search` searches the live custom MCP catalog and returns exact IDs and schemas.
- `mcp_tool_call` invokes one exact ID with its normal arguments.

Managed passthrough exposes the same gateways through the injected Kompile server. This keeps Codex, Gemini/Qwen, OpenCode, and Pi provider configuration small instead of injecting every third-party schema into every provider. Claude natively owns portable project `.mcp.json` servers, so Kompile marks its nested stdio bridge to skip those project entries (preventing duplicate launches); user-scoped servers still use the gateway. Ask the agent to search for the server/tool first, then call the exact returned ID when using the gateway.

Every custom stdio child receives an internal nesting depth. If a custom launcher invokes `kompile mcp-stdio` again, the nested process serves its normal Kompile tools but does not recursively reload custom servers.

## OAuth for remote servers

Streamable HTTP servers that require OAuth 2.1 log in with the shared managed credential store:

```bash
kompile mcp auth login docs
kompile mcp auth status docs
kompile mcp auth logout docs
```

Kompile discovers the server's authorization metadata, registers a client dynamically when the server advertises a registration endpoint, runs browser Authorization Code + PKCE (S256), and stores access/refresh tokens in the same locked, POSIX-private `~/.kompile/auth.json` store used for provider logins. Tokens are refreshed automatically at connect time and injected as `Authorization: Bearer ...` — never written to `.mcp.json`. Token entries are namespaced per server as `mcp-server-<name>`, keeping each external server's credentials in one dedicated channel-scoped entry.

For servers without dynamic client registration, register a client manually and supply credentials with `--header-env` or `--bearer-token-env-var`.

## Rich content and prompts/resources

`mcp_tool_call` propagates all MCP content blocks: text stays inline, images/audio become `attachments` metadata entries (`<type>:<mime>;base64,<data>`), embedded resources are inlined with their URI header, and `structuredContent` is preserved verbatim under metadata. `mcp_tool_search` also lists remote `prompts` and `resources` catalogs for servers that declare those capabilities.

## Configuration format

The CLI writes the portable `mcpServers` format:

```json
{
  "mcpServers": {
    "example": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@example/mcp"],
      "env": {"EXAMPLE_TOKEN": "$EXAMPLE_TOKEN"},
      "cwd": ".",
      "timeoutSeconds": 300,
      "enabled": true,
      "required": false,
      "includeTools": ["read"],
      "excludeTools": ["delete"]
    },
    "remote": {
      "type": "http",
      "url": "https://mcp.example.com/mcp",
      "envHeaders": {"X-Api-Key": "EXAMPLE_API_KEY"},
      "bearerTokenEnvVar": "EXAMPLE_BEARER_TOKEN"
    }
  }
}
```

Legacy entries with only `command` are treated as stdio. Entries with only `url` are treated as legacy SSE for compatibility. Set `"type": "http"` for modern Streamable HTTP.

## Open-source harness comparison

The lifecycle follows the common denominator of the major open-source harnesses:

| Capability | Kompile | Claude Code | Codex CLI | Gemini/Qwen | OpenCode |
|---|---:|---:|---:|---:|---:|
| Add/list/get/remove CLI | Yes | Yes | Yes | Yes | list/auth plus config |
| Project and user scope | Yes | Yes | Yes | Yes | Config precedence |
| Stdio | Yes | Yes | Yes | Yes | Yes |
| Streamable HTTP | Yes | Yes | Yes | Yes | Yes |
| Legacy SSE | Yes | Yes (deprecated) | No | Yes | Remote compatibility |
| Env and remote headers | Yes | Yes | Yes | Yes | Yes |
| Enable/disable | Yes | Config/lifecycle | `enabled` config | Yes | `enabled` config |
| Tool allow/deny | Yes | Tool policy | Yes | Yes | Tool globs |
| OAuth login/token store | Yes (`mcp auth`) | Yes | Yes | Yes | Yes |
| MCP prompts/resources in chat | Yes (via gateway) | Harness-dependent | Server instructions/tools | Yes | Tools-focused |

Primary comparison sources: Claude Code MCP documentation, OpenAI Codex MCP documentation and `mcp_cmd.rs`, Gemini CLI `mcp-server.md`, Qwen Code MCP documentation, OpenCode MCP server documentation, and the MCP 2025-06-18 transport specification.

The deliberate remaining gaps are custom MCP server instructions and live reload within an already-running session. The legacy SSE adapter now honors the configured timeout but keeps its older framing behavior; prefer Streamable HTTP.
