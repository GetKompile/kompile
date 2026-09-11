# Authentication, Channels, and Sources

`kompile auth` owns local LLM credentials, external channel connections, and source/workspace
integrations. LLM credentials remain local to the CLI. Integration credentials are sent only to
the owning server persona, encrypted there, and never returned by read APIs. The obsolete `kompile agent channel` command has been removed.

## LLM providers

```bash
kompile auth login openai --oauth
kompile auth login anthropic --stdin --name work
kompile auth switch anthropic work
kompile auth list
kompile auth logout anthropic work
```

## Channel providers

The quickest path is interactive: `kompile auth channel login` with no arguments walks through
provider selection, OAuth sign-in where supported, engine choice, masked credential entry,
enable, and an optional test message.

For scripts and non-interactive use, the running admin process is the source of truth for
installed providers and their fields:

```bash
kompile auth channel providers
kompile auth channel engines
kompile auth channel providers --json
```

Built-in providers are Telegram, Slack, Discord, WhatsApp, and email. Connections are named, so
multiple workspaces or accounts can use the same provider.

Channel auth remains distinct from document-source OAuth, but the authoritative OAuth service now
offers a fixed channel-purpose Slack profile. Slack OAuth can supply the workspace bot token only;
Socket Mode still needs a server-side app token. Discord and Telegram still need bot credentials,
WhatsApp needs a Business access token plus webhook secrets, and email currently needs mailbox
credentials. `kompile auth channel auth-status [PROVIDER]` reports these requirements without
returning any token.

The interactive defaults are:

- `kompile auth channel login` — walks through provider selection, OAuth sign-in where supported,
  engine choice, masked credential entry, enable, and an optional test message.
- `kompile auth source login [PROVIDER]` — starts OAuth for a source provider; omitting PROVIDER
  shows a numbered picker over the installed providers.

Every connection selects one inbound execution engine with `--engine`:

- `REACT` runs the named in-process KClaw agent with its tools and persisted channel history.
- `KOMPILE_CLI` runs `kompile exec --json`, preserving channel history and an optional `--model`.
- `WEB_CHAT` uses the same conversation service as the web chat process on port 8081 through a
  nonce-bound internal HMAC endpoint. Channel conversation IDs are opaque HMACs and are never
  exposed through predictable public chat-history keys.

`kompile auth channel engines` reports live readiness. An enabled connection fails closed when its
selected engine is unavailable rather than accepting messages that cannot be answered.

### Secret input

Channel commands intentionally have no literal `--token` or `--password` option. Read secrets from
the environment, a restricted file, masked interactive input, or one line of standard input:

```bash
--secret-from-env botToken=TELEGRAM_BOT_TOKEN
--secret-file appToken=$HOME/.secrets/slack-app-token
--secret-stdin password
```

The admin process creates a `0600` bearer credential at
`<kompile.data.dir>/config/channel-admin.token`. The local CLI checks the current project first and
then global `~/.kompile`; it never sends a file-backed token to a remote host. Remote clients must
use HTTPS and provide the credential through `KOMPILE_CHANNEL_ADMIN_TOKEN` (never an argv option).
Every control-plane request is authenticated, including reads.

The web console never receives that long-lived bearer. Authorize a browser with:

```bash
kompile auth channel web-login
```

Enter the resulting one-time, five-minute code in **Agent Hub → Channels**. The server exchanges it
for a 12-hour HttpOnly, SameSite=Strict cookie and a mutation CSRF token. Codes are 256-bit, stored
only as hashes in the shared data directory, and atomically consumed once across the admin, chat,
and crawl JVMs. Session revocation is shared across those JVMs as well. Remote browser exchange and
administration require HTTPS.

### Telegram

```bash
export TELEGRAM_BOT_TOKEN=...
kompile auth channel connect telegram --name support \
  --secret-from-env botToken=TELEGRAM_BOT_TOKEN \
  --engine REACT --non-interactive
kompile auth channel run support
kompile auth channel telegram pair support
# Send the printed one-time /pair command to the bot, then:
kompile auth channel telegram status support PAIRING_ID
kompile auth channel telegram approve support PAIRING_ID --chat-id 123456
```

An empty allowlist denies inbound messages. Use `--set allowAllInbound=true` only when accepting
messages from every chat is intentional.

Telegram polling refuses to start while a webhook owns the bot. Inspect and explicitly take over a
bot with:

```bash
kompile auth channel telegram webhook support
kompile auth channel telegram delete-webhook support
kompile auth channel telegram diagnostics support
```

Pairing codes expire after ten minutes, are stored only as hashes, and capture the first private or
group chat that presents the code. Group approval warns that every group participant is authorized.
Polling checkpoints are persisted per named connection before dispatch, so restarts do not replay
already accepted updates. Disconnecting removes the bot-bound checkpoint so a different bot can be
connected under that name.

### Slack

Slack uses a bot token for Web API delivery and an app-level token for Socket Mode inbound events.
Both are required for channel connections so a connection advertised as inbound cannot silently run
outbound-only.

For OAuth-backed Slack, authorize the fixed conversation scope profile and keep the Socket Mode app
token in server environment or encrypted channel storage:

```bash
kompile auth channel login slack
kompile auth channel auth-status slack
export SLACK_APP_TOKEN=xapp-...
kompile auth channel connect slack --oauth --name operations \
  --set allowedChannelIds=C01234567 --set allowHarnessSend=true \
  --enable --non-interactive
```

Source-only Slack grants fail closed until reauthorized with channel scopes. The OAuth token is read
from the shared encrypted OAuth store at runtime and is never copied into the channel connection
file. A first release permits one OAuth-backed Slack connection per project; reauthorization updates
that runtime. The `xapp-...` app token is not issued by workspace OAuth.

```bash
export SLACK_BOT_TOKEN=xoxb-...
export SLACK_APP_TOKEN=xapp-...
kompile auth channel connect slack --name operations \
  --secret-from-env botToken=SLACK_BOT_TOKEN \
  --secret-from-env appToken=SLACK_APP_TOKEN \
  --set allowedChannelIds=C01234567 --enable --non-interactive
```

`respondToAllMessages` defaults to false, so inbound handling is mention-driven.

### Discord

```bash
export DISCORD_BOT_TOKEN=...
kompile auth channel connect discord --name community \
  --secret-from-env botToken=DISCORD_BOT_TOKEN \
  --set allowedGuildIds=123456789 --enable --non-interactive
```

### WhatsApp

```bash
export WHATSAPP_ACCESS_TOKEN=...
export WHATSAPP_VERIFY_TOKEN=...
export WHATSAPP_APP_SECRET=...
kompile auth channel connect whatsapp --name support-wa \
  --secret-from-env accessToken=WHATSAPP_ACCESS_TOKEN \
  --secret-from-env verifyToken=WHATSAPP_VERIFY_TOKEN \
  --secret-from-env appSecret=WHATSAPP_APP_SECRET \
  --set phoneNumberId=123456789 \
  --set allowedPhoneNumbers=15551234567 --enable --non-interactive
```

Configure Meta's callback URL as
`https://ADMIN_HOST/api/kclaw/channels/webhook/whatsapp`. Verification is routed by the stored verify
token; message delivery requires Meta's `X-Hub-Signature-256` HMAC and the configured phone number
ID. Remote callback traffic must use HTTPS. Valid batches are partitioned by phone-number
connection, persisted and deduplicated by message ID, and acknowledged with HTTP 202 before any
agent runs. The durable worker claims each event before execution, preventing Meta retries or a
restart from repeating a tool turn.

### Email

```bash
export KOMPILE_EMAIL_PASSWORD=...
kompile auth channel connect email --name support-mail \
  --set imapHost=imap.example.com --set smtpHost=smtp.example.com \
  --set username=bot@example.com --set fromAddress=bot@example.com \
  --set trustedAuthenticationServer=mx.example.com \
  --set allowedSenders=operator@example.com \
  --secret-from-env password=KOMPILE_EMAIL_PASSWORD \
  --enable --non-interactive
```

Email always uses certificate-verified IMAPS. SMTP uses required STARTTLS (or implicit TLS on port
465). Inbound agent commands require the first Authentication-Results record to come from the
configured trusted receiving MTA and report aligned `dmarc=pass`, in addition to the exact sender
allowlist. Replies go only to that authenticated From address (not an untrusted Reply-To). The connection validates IMAP before reaching `RUNNING`; SMTP failures are
reported by test or delivery commands instead of being swallowed.

## Connection lifecycle

```bash
kompile auth channel list
kompile auth channel status operations
kompile auth channel configure operations --set allowedChannelIds=C01,C02
kompile auth channel rotate operations --secret-from-env botToken=NEW_SLACK_BOT_TOKEN
kompile auth channel run operations
kompile auth channel test operations --target C01 --message "Kompile is connected"
kompile auth channel disable operations
kompile auth channel disconnect operations --yes
```

Enabled connections are restored when the admin process restarts. Status output lists only the names
of configured secret fields, never their values. A supervisor retries unhealthy enabled runtimes
every 15 seconds, including web-chat connections when the chat process starts after admin. Channel
history is isolated by named connection, provider thread/topic, and user; files use hashed names in
a `0700` directory with `0600` contents.

The admin console's **Agent Hub → Channels** tab uses the same descriptor-driven API as the CLI. It
supports all built-in providers, engine/model selection, write-only credential rotation, delivery
tests, enable/disable/disconnect, and the complete Telegram pairing and webhook workflow. The web
surface uses a server-issued browser session; the long-lived admin bearer is never stored in
JavaScript.

### Harness access

The chat/MCP harness exposes the `channel` tool in the `integrations` tool group. It accepts no
secret or provider-setting arguments. It can discover providers, report auth readiness, initiate
human-approved login, list/status named connections, and deliver through an existing connection.
Delivery is rejected unless the operator set `allowHarnessSend=true`, the connection is running,
and the requested target is in that provider's explicit allowlist. OAuth authorization URLs may be
shown to the human, but access/refresh/bot tokens never enter model context or tool results.

## Source and workspace integrations

`kompile auth source` owns source OAuth plus bilateral workspace connections. These are deliberately
separate from conversational channels: Notion pages, Obsidian vaults, folders, and Git repositories
sync content; they do not pretend to have a message/reply transport.

```bash
kompile auth source providers
kompile auth source configure-oauth notion \
  --client-id "$NOTION_CLIENT_ID" \
  --client-secret-from-env NOTION_CLIENT_SECRET
kompile auth source validate-oauth notion
kompile auth source login notion
kompile auth source oauth-status notion
kompile auth source oauth-health notion
kompile auth source oauth-settings notion
```

OAuth client secrets are encrypted with AES-256-GCM in the project `0600` secrets sidecar. Google,
Microsoft, Atlassian, Notion, Slack, Discord, and Reddit authorization runs through the crawl-manager source
persona; callback pages report success/failure and close an OAuth popup instead of redirecting to a
nonexistent route. Discord OAuth is account/guild discovery only; conversational and history access
still uses an explicitly configured bot token. Reddit requests only the `identity` and `read` scopes used
for public post/comment ingestion.

The CLI mirrors the web OAuth management lifecycle rather than implementing a second token store:

```bash
# All settings and setup guides are safe, masked read APIs.
kompile auth source oauth-settings
kompile auth source oauth-setup google

# Refresh/revoke the connected account, or remove the OAuth application registration.
kompile auth source refresh google
kompile auth source disconnect-oauth google
kompile auth source reset-oauth google --yes
```

`reset-oauth` deletes the configured client ID/scopes and encrypted client secret; it requires
`--yes`. It is distinct from `disconnect-oauth`, which revokes/removes the connected user tokens but
preserves the OAuth application configuration.

For one-off ingestion, `kompile auth source ingest` consumes the live source catalog, rejects a
connector whose runtime is not installed, and accepts credentials only through environment, file,
or stdin references. `--fact-sheet-id` is mandatory and must be positive; one-off ingestion never
falls back to mutable global active-sheet state:

```bash
# Discord history uses bot-only message access.
export DISCORD_SOURCE_BOT_TOKEN=...
kompile auth source ingest discord --path 123456789012345678 --fact-sheet-id 7 \
  --secret-from-env botToken=DISCORD_SOURCE_BOT_TOKEN \
  --set channelIds=234567890123456789 --set daysBack=30

# Slack can use the encrypted Slack OAuth connection, so no literal token is needed.
kompile auth source ingest slack --path C01234567 --fact-sheet-id 7 --set limit=500

# Jira uses the shared Atlassian OAuth connection and resolves the matching cloud from this site URL.
kompile auth source ingest jira --path https://company.atlassian.net --fact-sheet-id 7 \
  --set projectKey=APP --set maxIssues=500 --set includeComments=true

# Reddit uses the encrypted Reddit OAuth connection.
kompile auth source configure-oauth reddit --client-id "$REDDIT_CLIENT_ID" \
  --client-secret-from-env REDDIT_CLIENT_SECRET
kompile auth source login reddit
kompile auth source ingest reddit --path r/programming --fact-sheet-id 7 \
  --set sortType=top --set timePeriod=month --set postLimit=250 --set includeComments=true

# Remote stores keep both access keys out of argv.
kompile auth source ingest s3 --path company-bucket/reports --fact-sheet-id 7 \
  --secret-from-env accessKey=AWS_ACCESS_KEY_ID \
  --secret-from-env secretKey=AWS_SECRET_ACCESS_KEY --set region=us-east-1
```

`--set` rejects token/password/secret-like keys. Those fields must use one of the `--secret-*`
forms. Runtime credentials are stripped recursively before source metadata reaches documents,
indexes, or the knowledge graph.

Add `--local` to run the same Jira, Reddit, Notion, Confluence, Slack/Discord history,
IMAP/Gmail, Google Docs/Drive/Workspace, or OneDrive loader in the current folder's MCP-owned
crawl runtime without starting crawl-manager. Local mode does not read the server's OAuth database,
so pass the connector token/password with a `--secret-*` option. The local request and durable job
transcript redact those values, and each remote item is materialized under
`data/knowledge-sources/<kb>/external/` before entering the normal Markdown/chunk/graph pipeline:

```bash
export REDDIT_ACCESS_TOKEN=...
kompile auth source ingest reddit --local --path r/programming --fact-sheet-id 7 \
  --secret-from-env accessToken=REDDIT_ACCESS_TOKEN \
  --set sortType=top --set postLimit=250 --set includeComments=true

export NOTION_TOKEN=...
kompile auth source ingest notion --local \
  --path NOTION_PAGE_OR_DATABASE_ID --fact-sheet-id 7 \
  --secret-from-env apiToken=NOTION_TOKEN --set resourceType=database --set maxPages=250
```

Use `--skip-final-learning` when the immediate goal is document materialization, chunking,
and indexing and the final KGE/FOL/PSL/MEBN learning pass should be run separately. Source
extraction, provenance, and the searchable corpus are still persisted. The former
`--skip-reasoning-learning` spelling remains an alias.

A local Obsidian vault also accepts `ingest obsidian --local --path /notes/vault`; it is crawled
as Markdown files. Obsidian Local REST synchronization remains the separate connection workflow
below because it is bilateral rather than a one-shot source crawl.

### Notion

After completing Notion OAuth, connect a page or database scope to a Fact Sheet:

```bash
kompile auth source connect notion --fact-sheet-id 7 \
  --scope NOTION_PAGE_OR_DATABASE_ID --direction BIDIRECTIONAL --enable
kompile auth source test CONNECTION_ID
kompile auth source sync CONNECTION_ID
```

Notion authentication tests call `/users/me`; they no longer return a synthetic success. Webhook
ingress is owned by crawl-manager at `https://CRAWL_HOST/api/sync/webhook/notion`, requires an
encrypted-at-rest HMAC secret, and never degrades to unauthenticated mode. `--enable` runs that
provider authentication test first and leaves a failing connection disabled.

### Obsidian and local Markdown

Use a local vault directly, or the Obsidian Local REST API without putting its token in argv:

```bash
# Local vault
kompile auth source connect obsidian --fact-sheet-id 7 --scope /notes/vault --enable

# Local REST API
kompile auth source connect obsidian --fact-sheet-id 7 --scope /notes/vault \
  --api-url https://localhost:27124 --token-from-env KOMPILE_OBSIDIAN_TOKEN --enable
```

Local folder and Git Markdown connections use the same lifecycle. Git HTTPS tokens use
`--token-from-env`, `--token-file`, or `--token-stdin`; system Git/SSH credentials remain available
without copying them into Kompile.

```bash
kompile auth source connect local-folder --fact-sheet-id 7 --scope /knowledge --enable
kompile auth source connect git-repository --fact-sheet-id 7 --scope /work/wiki \
  --repository-url git@github.com:example/wiki.git --branch main --enable
kompile auth source list --fact-sheet-id 7
kompile auth source pull CONNECTION_ID
kompile auth source disable CONNECTION_ID
kompile auth source delete CONNECTION_ID --yes
```

The crawl web app exposes the same connection dialog in **Source Maintenance**, including Notion,
Obsidian, local folders, and Git repositories. Run `kompile auth source web-login` for its one-time
browser authorization. The portable signed session is verified by every Kompile persona, while
path-scoped HttpOnly cookies prevent source credentials from being sent to unrelated chat APIs.
Remote crawl and document mutations use the same session and CSRF proof; trusted loopback CLI/MCP
crawls remain local-first.

### Additional source loaders

Both crawl-manager and the folder-local JVM crawl runtime package Jira, Reddit, Notion,
Slack/Discord history, email/IMAP, Gmail, Google Docs/Workspace, Drive, OneDrive/SharePoint, and
Confluence implementations. Crawl-manager additionally packages specialized local
MBOX/Maildir/PST/Apple Mail parsers; local mail archives and Obsidian vaults remain ordinary
filesystem sources in local mode. S3, SFTP, SMB, and SQL are reported available only when a matching
crawler is installed; SFTP and SMB additionally probe their required system clients. Gmail, Docs,
Workspace, Drive, OneDrive, Slack, Confluence, Jira, and Reddit resolve encrypted OAuth connections automatically;
explicit `accessToken` properties remain an advanced per-request override. Discord history requires
a Discord bot token—the user OAuth flow is not substituted for bot-only message-content access.
Atlassian OAuth selects the accessible cloud resource matching the supplied Confluence or Jira site URL
(or an explicit `cloudId`) and calls the corresponding `api.atlassian.com/ex/{product}/{cloudId}` API.
Jira accepts only the HTTPS root of an `*.atlassian.net` Cloud site; bearer overrides require an explicit
`cloudId`, and an email/API-token override must supply both values. The browser pins Jira and Reddit crawls
to the selected Fact Sheet instead of consulting mutable global active-sheet state.
Reddit ingestion uses permanent OAuth refresh tokens and applies bounded post, comment, score, time,
and NSFW filters before content enters the crawl pipeline.

SFTP uses OpenSSH batch mode, never a remote shell command, and supplies password auth through the
`SSHPASS` environment rather than argv. SMB uses an owner-only temporary authentication file and
rejects command-language metacharacters in remote paths; passwords are never placed in argv.

Provider descriptors are advertised only when the crawl persona packages a live loader or crawler;
an icon/form descriptor alone is not treated as a usable connector.
