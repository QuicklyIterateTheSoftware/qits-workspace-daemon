# qits-workspace-daemon

Everything qits runs **inside** a workspace container. One binary, one workspace, one container
lifetime.

A workspace is a branch ref in a repository's bare origin plus a container that clones that branch
into `/workspace`. This binary is that container's PID-1 child. It provisions the checkout, keeps it
synced with its origin, supervises the repository's dev servers, serves the working tree, and runs
the commands and coding agents the user drives from the browser. Everything on the host's side of the
boundary belongs to
[qits-workspaces](https://github.com/QuicklyIterateTheSoftware/qits-workspaces).

    ./mvnw verify     # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `workspace-daemon-protocol/` | The control-plane wire contract: message records + a codec over a plain `Map`. Depends on nothing. qits-workspaces vendors a byte-identical copy. |
| `workspace-daemon-files/` | Reading the checkout: file listing, content, lazy directories, gitignore. |
| `workspace-daemon-detection/` | Framework detection and the component map, over `workspace-daemon-files`. |
| `qits-commands/` | Launching and supervising processes: the PTY, the process registry, chat transports, the in-memory command store and log buffer. |
| `qits-coding-agents/` | The Claude Code and Kimi harnesses: launch rendering, the ACP chat client, transcript import, plugins, auth. Depends on `qits-commands` — a coding agent *is* a command. |
| `workspace-daemon/` | The Quarkus application: the control socket, the HTTP API, the hook webhook, provisioning, git, service supervision. Wires every module above by hand. |

The first five are **framework-free**: no Quarkus, no CDI, no JAX-RS, no Jackson. They are plain
jars with plain constructors that `ControlSocket` news up. That is not stylistic — it is what keeps
the GraalVM native image small and free of anything the builder has to be told about by hand.

## The two channels

**The control socket** — the daemon dials `ws://<host>/workspaces/daemon/{workspaceId}` on boot and
keeps it open. Provisioning progress, bootstrap steps, service transitions, working-tree status,
agent activity and change nudges all ride it. Message shapes live in `workspace-daemon-protocol`;
`DaemonProtocol.CAPABILITY_VERSION` is what a backend branches on. The path is qits-workspaces'; the
daemon dials the url it was handed verbatim and parses no path out of it.

**The HTTP API** — a bearer-authenticated server on `:13338` serving the working tree, the commands
surface, the coding-agent surface, and the two interactive websockets. It **does not bind** without
`qits.workspace-daemon.api-token`: it is reachable from the whole docker network and serves an
untrusted checkout, so it is never served anonymously.

| | |
|---|---|
| `GET /files`, `/files/content`, `/detection`, `/component-map` | the checkout |
| `POST /fast-forward`, `/update-from-parent` | parent integration |
| `GET·POST /commands`, `GET /commands/actions`, `GET /commands/{id}`, `GET /commands/{id}/log`, `POST /commands/{id}/terminate` | commands |
| `POST /agents`, `GET /agents/available`, `GET /agent-sessions`, `GET·POST /agent-plugins`, `POST /prompt-refinements` | coding agents |
| `WS /terminal/commands/{id}`, `WS /chat/commands/{id}` | the interactive half |

No `{repoId}/{workspaceId}` prefix anywhere: this daemon serves exactly one workspace, so those
segments would be a constant the caller has to get right. The response bodies carry both ids back.

These paths are **not** under the `/<segment>/…` convention the six services adopted, and that is
deliberate. Each of those six took its own gateway segment, and the gateway routes it verbatim by
prefix — `/<segment>/*` → `qits-<segment>`, no rewriting — so the service serves the prefixed path
itself. The daemon is one process per workspace container rather than a single service behind a
segment, so its addressing is a different question, and it was left undecided rather than guessed
at. The surface is unreachable in a host-created container regardless, for an unrelated reason —
`migration-plan.md` §9 item 16: no gateway route and no `QITS_WORKSPACE_DAEMON_API_TOKEN` injected,
so it does not bind at all. That item now also blocks the six `services`/`bootstrap-commands`
capabilities, whose host-side routes were deleted when the conventions landed.

## What the daemon does not keep

**Nothing outlives the container.** There is no datasource, no Hibernate, no Panache, no Flyway. The
command list, the command logs, the agent-session index and the transcript aggregates are all
in-memory maps, and a container recreate starts them empty.

This is the deliberate scope of moving commands and agents inside, and it costs:

- the Commands list and its logs are **empty** for a workspace whose container was recreated;
- resuming or forking an agent session from a previous container is **refused** — it fails closed,
  because the store that would vouch for the session did not survive;
- token and message-count aggregates are lost.

Transcripts themselves are safe: the harness writes them under `/claude-home`, a volume shared
across workspaces that outlives any container. What became ephemeral is the *index* of them.

Two bounds exist because heap here is a container sized for the workspace's own build, not a host
with a disk: `CommandStore.MAX_COMMANDS` (200 finished commands, oldest evicted, running commands
never evicted) and `CommandLogBuffer.DEFAULT_CAPACITY` (50,000 lines per command).

## What it does not carry, and why

**No Jackson.** JSON is `io.vertx.core.json`, which is already in the image via `quarkus-vertx` and
pulls only the streaming core. A second JSON stack would mean databind reflection to register.
`qits-coding-agents` ports three Jackson-heavy readers onto it through `agents/json/Json`, a
read-only view with Jackson's missing-node semantics — see that class's javadoc for why a direct
translation would not have been safe.

**No pty4j.** It is JNA plus per-platform `.so` files extracted at runtime, which is the worst case
for a native image. `ForeignPty` calls libc through `java.lang.foreign` instead. Every
`FunctionDescriptor` is a `static final` constant on purpose — that is what lets GraalVM register
the downcall stubs at build time with no `Feature` and no config file. **If you add a downcall, keep
it constant.**

**No JAX-RS.** `WorkspaceApi` routes with a `switch` over `request.path()` on a raw vertx
`HttpServer`.

## What the daemon dials out to

Four different services, on four hosts on `qits-net`. Every address follows
`/<segment>/(api|mcp|git|daemon)/…`, served by the owning service verbatim — through the gateway
*and* when a container is dialled directly, so the prefix is never something to strip on the inside.

| What | Path | Served by | Where the host comes from |
|---|---|---|---|
| the control socket | `/workspaces/daemon/{workspaceId}` | qits-workspaces | `qits.workspace-daemon.url`, injected — the whole url, dialled verbatim |
| the self-clone origin | `/artifacts/git/{repoId}` or `/artifacts/git/{projectId}/{repoName}` | qits-artifacts | `qits.workspace-daemon.git-base-url` if injected, else **derived** |
| MCP `repository` | `/projects/mcp` | qits-projects | `qits.repository-mcp.url` if set, else **derived** |
| MCP `observability` | `/observability/mcp` | qits-observability | `qits.observability-mcp.url` if set, else **derived** |
| MCP `actions` | — | nobody | `qits.actions-mcp.url` only; unset ⇒ an ACTIONS launch fails saying so |

**"Derived" means the authority of the control-socket url, and that is an assumption.** It is only
right where one authority routes every segment — i.e. where the daemon was handed the gateway. Only
one address is genuinely told to the container today, and it is qits-workspaces'. So each derivation
announces itself: the git base as a `DaemonLog` `WARN` at provision time, the MCP hosts as a `WARN`
at agent-wiring time. Nothing here silently invents a host and then fails as a 404 nobody sees. What
is still unsettled at the gateway is a capability question rather than a naming one — how websockets
pass through it with `SameOriginUpgradeCheck` still seeing a real `Origin`/`Host`, and it wants one
answer for all sockets rather than one per route. So the topology is not decided here — only made
visible and overridable.

## Configuration

Everything is `qits.workspace-daemon.*`, injected per container as `QITS_WORKSPACE_DAEMON_*`. The
identity values (`workspace-id`, `repository-id`, `branch`, `parent`, `project-id`, `repo-name`) are
`Optional<String>` deliberately: SmallRye treats an empty default as "no value" and fails to resolve
a plain `String` when the env is absent.

`ControlSocket` is the **single reader** of every setting the capability modules need, and hands them
in as constructor arguments. That is load-bearing for `hooks-port` and `qits.workspace.claude-mount`
in particular: the hook webhook binds one and the agent launch renders it into every hook `curl`, so
two independent reads that disagree would leave agents running fine and silently never reporting
lineage or activity.

## Where the code came from

Extracted from the qits monolith. `qits-commands` and `qits-coding-agents` were **reimplemented**
rather than history-replayed: they target a different runtime (in-container, no database), so a
`git filter-repo` of the originals would have carried a persistence layer and a `docker exec`
transport that have no meaning here. DTO field names were kept exactly, because they are a wire
contract the SPA consumes unchanged.
