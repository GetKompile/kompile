# CLI-to-CLI peers

`kompile peer` and the `peer` MCP/chat tool share one implementation. No web app is required. Bare `kompile peer` opens a wizard. Native chat, stdio MCP, and daemon/socket MCP register the tool; progressive clients activate `integrations` to discover it.

## Pair two machines

On B, in the project whose agents should receive messages:

```sh
kompile peer init --yes
kompile peer listen --bind 0.0.0.0 --port 7337 --only skills,memories,roles,transcripts,judge-records,cli-settings,credentials --yes
```

`init` creates owner-only `~/.kompile/peer/identity/` containing `identity.p12`, `password`, and `token`. It needs JDK `keytool` (Java home/bin, otherwise PATH), including with a native CLI. Existing identities are never replaced. The listener prints its SHA-256 certificate fingerprint, not its token. Default bind is loopback; choose a reachable interface and allow the port in your firewall. Ctrl+C stops a CLI listener. MCP `listen` starts a host-owned daemon listener; `stop` closes only that host's listener. A separate CLI invocation cannot stop another CLI process; use Ctrl+C in that listener. No automatic listener startup after a host restart.

Copy **only B's token** to an owner-only file on A through a trusted channel, and independently verify B's fingerprint. Never share B's private key/password. On A:

```sh
chmod 600 /private/path/b-token
kompile peer add b --address tls://B_ADDRESS:7337 --fingerprint B_SHA256 --token-file /private/path/b-token --yes
kompile peer status b
kompile peer sync b --only skills,transcripts,judge-records,cli-settings,credentials
kompile peer sync b --only skills,transcripts,judge-records,cli-settings,credentials --apply --yes
```

The first sync previews; the second applies. Default direction is bidirectional; `--direction push|pull|both` selects direction. Conflicts abort before writes; explicitly choose `--conflict local|remote` if appropriate. Deletions require `--allow-delete` on caller and listener. Baselines bind local/remote profile mounts and TLS certificate identity. Use separate peer names for different profiles; remove and explicitly re-pair to change identity. Existing `kompile sync b` also accepts these registered TLS peers with its existing flags. SSH/local-subprocess sync remains supported.

Repeat pairing in the other direction if B must initiate requests to A. A listener does not initiate reverse connections, automatically sync in the background, or traverse NAT. Addresses are exactly `tls://host:port`, without plaintext, URL path, userinfo, query, or fragment. TLS verifies the pinned certificate **before** sending the token. A token grants access to the listener's selected families and project agent mailbox. There is no per-client grant isolation behind one token. To revoke it, stop the listener, replace the token with an owner-only 32-random-byte hex secret, restart, and explicitly re-pair trusted clients.

## Exactly what synchronizes

Defaults remain `skills,memories,roles`. Additional selections are explicit:

| Family | Allowlisted state |
|---|---|
| `prompts`, `provider-skills` | Existing portable Kompile families |
| `cli-settings` | Global `chat-config.json`, `harness-config.json`, `judge-defaults.json` |
| `credentials` | Global managed `auth.json` (API keys/file-backed OAuth credentials) |
| `transcripts` | Top-level `conversations/<session>.txt` |
| `judge-records` | `sessions/<session>/judgements.jsonl` only |
| `harness-settings`, `harness-credentials` | Existing explicit Codex/Claude mounts; see [harness sync](cli-harness-sync.md) |

`all` includes secrets. Settings, transcripts, and judge inputs can themselves contain credentials. Both sides must explicitly select/expose these families. They are global-only. Address listeners expose one fixed Kompile profile/project, not caller-selected filesystem roots. `--home` selects the local CLI profile; `--user-home` selects local external harness mounts. Existing SSH sync supports project-scope portable content.

**Close active chat/harness writers before applying snapshots.** Files are replaced, not merged. Concurrent target changes are rejected against inventory hashes; source payload hashes are checked. There is no transaction spanning all files: a mid-transfer failure can leave earlier writes applied, with the baseline unadvanced. Review the next plan before retrying. Credential refresh races cannot be made safe by copying a live auth store. Environment/keychain credentials are not exported; copied refresh tokens may invalidate the original login.

Symlinks/traversal/unallowlisted fetch/apply paths are rejected. Sensitive IO uses anchored no-follow secure directory handles and owner-only replacements; unsupported filesystems fail closed. Peer identity/token files require private POSIX permissions. Files are limited to 16MiB and frames to 24MiB; archive/split large transcripts first. Caches, models, PID/session ownership, resume claims, approvals/overrides, tool-results, locks and runtime state are **not** transferred. A copied transcript/judge log does not create a running session or remap historical working directories.

## Agent-to-agent messages

Run listeners in both agents' projects and pair in both directions:

```sh
kompile peer agents b
kompile peer send b --target-session REMOTE_COORDINATION_ID --message 'Please review this result'
kompile peer inbox --project /local/project --session LOCAL_COORDINATION_ID
kompile peer ack --project /local/project --session LOCAL_COORDINATION_ID --message-id MESSAGE_ID
```

Native or external CLI agents use the same tool:

```json
{"action":"agents","peer":"b"}
{"action":"send","peer":"b","targetSessionId":"remote-agent-id","message":"Please review this result"}
{"action":"inbox"}
{"action":"ack","messageId":"delivered-message-id"}
```

Messages are durable, target an active registered agent in the listener's project, and inherit the coordinator mailbox TTL. They have stable IDs and explicitly marked untrusted peer content. Inbox/ack tools are bound to the caller's local coordination session. Use `replyTo` when replying. Reading does not acknowledge; acknowledge after handling. Retries may duplicate messages; retain IDs if processing has side effects. Successful send means mailbox delivery, **not** model consumption: no automatic wake-up of idle native/headless/external turns. Agents cooperate by polling. Peer text is never an operator approval or permission bypass.

## Advisory peer judge calls

B explicitly enables `--allow-judge` when listening and has an available globally enabled configured judge. Judge requests use the host's default authenticated profile and the listener project's backend configuration. Custom `--home` listeners must leave remote judge calls disabled to avoid billing/credential profile mismatches. A asks:

```sh
kompile peer judge b --prompt 'Review the proposed result' --output-file result.txt --yes
```

Equivalent MCP request:

```json
{"action":"judge","peer":"b","prompt":"Review the proposed result","output":"Agent result","confirmed":true}
```

This discloses supplied text to B's judge/provider and may incur costs. The returned quality verdict is advisory: it executes no proposed command, modifies no live controls, imports no approvals/overrides, and does not implicitly reconfigure the caller's enforcement backend. Evaluation is bounded to 45 seconds; unavailable/failed evaluations return errors. Calls are not automatically inserted into native supervision. Existing local logs can separately be synced via `judge-records`.

## MCP operations

`peer` exposes `init`, `listen`, `stop`, `add`, `list`, `remove`, `status`, `local_status`, `agents`, `sync`, `send`, `inbox`, `ack`, `judge`. `confirmed=true` requires explicit operator authorization for profile/exposure changes, applied sync and judge usage. Delegated children may communicate/poll their own inbox but cannot change profile/exposure state or authorize judge usage. Parameters never accept raw tokens, arbitrary remote commands, remote roots or cross-session judge controls. Sync defaults to `dryRun=true`; apply with `dryRun=false,confirmed=true`. `local_status` reports the caller's coordination session ID and listener status.
