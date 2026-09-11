# CLI resource admission policies

`/resources` works in standard chat and emulated passthrough. It shows a readable
policy summary with configuration paths and the source of each setting; JSON is
an advanced format, not required for routine configuration. Settings are read on
each launch/watch poll. Permissions and subprocess memory limits remain independent.

## Configuration sources

Precedence, lowest to highest:

1. Built-in defaults.
2. User defaults: `~/.kompile/resource-policy.json`.
3. Project overrides: `<project>/.kompile/resource-policy.json`.

Fields inherit independently. An explicit `rules` array replaces the inherited
ordered list; this preserves existing full-policy files and rule removal semantics.
Editing a scalar writes only that override, not a snapshot of all defaults.
Editing rules saves the effective rule list to the selected scope.
`/resources inherit rules` returns to the inherited list. Existing saved policies
are not automatically rewritten. Project discovery resolves the registered project
root, falling back to the working directory when no registered project exists.

## Interactive wizard

Run `/resources setup` to configure rules, defaults, or inheritance through numbered
choices. `/resources add` jumps straight to adding a rule. Both standard chat and
emulated passthrough reuse their existing input reader—no JSON required.

The wizard asks for project/user scope, rule name, executable, optional argument
prefix, and resource class. High is the safe default. It shows a summary and
requires explicit confirmation before saving (default: no). `cancel`, Ctrl+C,
or EOF discards the operation. Invalid menu choices are re-prompted.
`/resources global add` fixes the scope to user settings.

## Commands

- `/resources show` (or bare `/resources`): readable effective policy and sources.
- `/resources sources`: configuration paths, precedence and setting origins.
- `/resources rules`: list the ordered rules (kept out of the brief default summary).
- `/resources check <shell command>`: explain classification without executing.
- `/resources rule <id> low|high <executable> [argument prefix...]`: prepend rules
  for both `bash` and `process launch`. Tokens match literally, not as substrings.
  Omitting the prefix matches every invocation of that executable.
- `/resources global <command>`: operate on user defaults rather than project settings.
  Project overrides still win on actual launches.
- `/resources inherit default|unknown-shell|rules`: remove the selected scope's override.
- `/resources json`: export the full effective JSON (advanced).
- `/resources add`: interactive add-rule wizard. Plain-text arguments also work
  like `rule`; legacy JSON input remains accepted for compatibility.
- `/resources remove <id>`: remove a rule.
- `/resources default low|high`: unmatched launch class.
- `/resources unknown-shell low|high`: unsupported shell syntax class.
- `/resources set <policy JSON>`: validate and atomically replace the policy.
- `/resources preview <tool> <arguments JSON>`: explain classification without execution.
- `/resources help`: syntax and fields.

`low` does not enter the exclusive high-resource lane or its RAM/GPU capacity probe.
`high` uses existing lane/capacity checks and watches. Async crawls retain their inner
capacity admission. Unknown work defaults high; editable low defaults cover literal
diagnostics such as `free`, `df`, `uname`, `nvidia-smi`, `date`, and `printf`, alongside
`git`, `ps`, `pwd`, `cd`, and `sleep`. Builds, tests, model work, crawls and unknown
scripts still default high. A lightweight command followed by a build remains high.
Invalid policy files fail conservatively high; help and explicit `set` repair remain usable.

For example (no JSON):

```text
/resources check free -m
/resources rule java-version low java -version
/resources check java -version
/resources remove java-version
/resources global unknown-shell high
```

Rules created by `rule` have `<id>-bash` and `<id>-process` IDs; removing the base ID
removes both. Duplicate IDs are rejected without changing the file.
Read-only/lifecycle actions do not enter admission.

## Typed rules

Policy fields: `defaultClass`, `unknownShellClass`, `rules` (ordered array).
Each rule: `id`, `tool`, `class` (`low`/`high`), `all` (AND conditions).
First matching rule wins per command. Highest class wins across a shell chain.
Descriptions are never concatenated into command classification.

Conditions use `path` (JSON Pointer), `op`, and typed JSON `value`:

| Path | Meaning |
|---|---|
| `/arguments/action` | Original tool arguments; any name/nested path supported |
| `/shell/executable` | Literal executable basename, including from absolute paths |
| `/shell/argv` | Exact argument tokens, excluding executable |
| `/shell/options/-Dtest` | String value in a `-Dtest=Name` token (before `--`) |
| `/shell/environment/MAVEN_OPTS` | Literal leading assignment or `env NAME=value` value |

Operators: `eq` (typed equality), `contains` (array membership), `sequence`
(consecutive array tokens), `prefix` (initial array tokens), `exists` (boolean), `gte`/`lte` (numeric comparisons).
Use `sequence` with `["-pl", "module"]` for space-separated flag/value pairs.
JSON Pointer escaping applies: `/` in keys becomes `~1`, `~` becomes `~0`.

Example for a **known small test**, not all Maven tests:

```text
/resources add {"id":"small-test","tool":"bash","class":"low","all":[{"path":"/shell/executable","op":"eq","value":"mvn"},{"path":"/shell/argv","op":"contains","value":"test"},{"path":"/shell/argv","op":"sequence","value":["-pl",":my-module"]},{"path":"/shell/options/-Dtest","op":"eq","value":"MySmallTest"}]}
/resources preview bash {"command":"/home/agibsonccc/dev-apps/mvn/bin/mvn -pl :my-module -Dtest=MySmallTest test"}
```

Add a separate rule with `tool: "process"` for background launches. Prepend high
rules for flags such as `-am` or native build options when appropriate. Rules are
user-configured cost estimates, not measured memory usage.

The parser supports literal quotes, escaping, leading environment assignments, `env NAME=value`, and separators (`&&`, `||`, `;`, `|`,
`&`, newline). Expansions, redirects, control syntax and wrappers (`bash -c`,
`sudo`, `timeout`, etc.; `env` with options other than `--`) use `unknownShellClass`; they are not partially interpreted
to justify a low result. This is not a full Bash interpreter or a security sandbox.

Rules apply to guarded launch actions: bash, process launch, production crawls,
model-runtime mutations, pipeline run/test, graph embedding training, code indexing,
and evaluation execution. Status/output/cancel remain available during heavy work.

## Process and queue behavior

Background launches publish the resolved class. State updates retain it; subsequent
policy edits cannot turn an already-running low process into a blocker. Legacy
process entries without a class use the current policy. Retained high reservations
are not revoked by configuration changes.

Blocked tool-call watches reevaluate their tool/arguments against current policy.
Reclassifying waiting work as low makes it eligible to wake, without auto-execution.

Explicit `edit_coordinator` checks accept `action: "preflight_activity"` or
`"watch_activity"`, `tool_name: "bash"`, and `tool_arguments: {"command": "..."}`.
The old kind-only form remains a high-resource check (it has no arguments to classify).
Execution/preflight results include `resourceClassification` evidence.
