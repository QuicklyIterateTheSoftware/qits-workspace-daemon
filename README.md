# qits-workspace-daemon

Everything qits runs **inside** a workspace container. One binary, one workspace, one container
lifetime.

A workspace is a container that hosts agents (qits-1152). It keeps one base clone of its wrapper at
`/workspace/base`, and each agent works in its own agent worktree made from it, at
`/workspace/agents/<agentId>/<repoName>`. This binary is that container's PID-1 child. It makes the
base clone, keeps it fetched, makes and removes agent worktrees, pushes the agents' branches, serves
each agent's files, and starts, stops and resumes each agent's harness. Everything on the host's side of the
boundary belongs to
[qits-workspaces-service](https://github.com/QuicklyIterateTheSoftware/qits-workspaces-service).

    ./mvnw verify     # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `workspace-daemon-protocol/` | The control-plane wire contract: message records + a codec over a plain `Map`. Depends on nothing. **Released**, as `eu.wohlben.qits:qits-workspace-daemon-protocol` — it also carries `WorkspaceImage`, the version of the image and daemon binary this release produced, which is how qits-workspaces pins them. |
| `workspace-daemon-files/` | Reading the checkout: file listing, content, lazy directories, gitignore. |
| `workspace-daemon-detection/` | Framework detection and the component map, over `workspace-daemon-files`. |
| `workspace-daemon/` | The Quarkus application: the control socket, the HTTP API, the hook webhook, the base clone, agent worktrees, origin sync and the agents' harnesses. Wires every module above by hand. |

The first three are **framework-free**: no Quarkus, no CDI, no JAX-RS, no Jackson. They are plain
jars with plain constructors that `ControlSocket` news up. That is not stylistic — it is what keeps
the GraalVM native image small and free of anything the builder has to be told about by hand.

### The harness, which is no longer here

`qits-commands/` and `qits-coding-agents/` used to be two more modules in this list. They are now
the released `eu.wohlben.qits:qits-commands` / `eu.wohlben.qits:qits-coding-agents`, built from
[qits-coding-agents](https://github.com/QuicklyIterateTheSoftware/qits-coding-agents) — because they
existed **twice**, once here and once inside qits-projects-daemon, under two package roots, and had
drifted: this copy grew `AgentPluginService`, `PromptRefinementService`, a third MCP scope and four
named pre-approved writes; the other grew the tickets desk's system prompt. Every harness change had
to be written twice, and the second copy is the one that kept being forgotten.

Both moved, not one: `qits-coding-agents` does not compile without `qits-commands`, and keeping one
here would have preserved exactly the split that let them diverge. What stayed is what is genuinely
*this daemon's*:

| Here | Why |
|---|---|
| `WorkspaceContext` | The repository and workspace ids a workspace has beyond a branch and a commit. The library asks only for `CheckoutContext`; a daemon's own identity is its own business. |
| `WorkspaceMcpServers` | Which MCP servers a scope attaches, narrowed, with their pre-approval lists. This daemon attaches three across three services; qits-projects-daemon attaches one. Neither is a default the other could fall back on. |
| `DaemonAgentDefaults` | The default harness, activity tracking and refinement model — plus the mounted configuration document and this container's ambient facts. |
| `AgentConfigurationFile` | Writing the injected document to disk at boot, before anything starts. |
| The route table | `WorkspaceApi`'s agent surface, `AgentJson`, and the hook webhook. |

**It is a released coordinate, and that changes the rhythm.** A change to shared harness code is
released there first and arrives here as a version bump in the root pom
(`qits.coding-agents.version`), so "the library is fixed" and "this daemon has the fix" are two
different moments. The library's own suite is where harness behaviour is proven — including this
daemon's rendered command lines, asserted byte for byte there against a fixture copy of
`WorkspaceMcpServers`.

It is also the one dependency this reactor resolves off Maven Central, which is why the root pom now
declares a `<repositories>` block. Inside CI and the Dockerfile's builder stage,
`.qits-maven-settings.xml` mirrors it onto the platform's in-network registry.

## The two channels

**The control socket** — the daemon dials `ws://<host>/workspaces/daemon/{workspaceId}` on boot and
keeps it open. Provisioning progress, agent activity (each frame naming its agent), each branch the
daemon pushed for an agent (`AgentBranchPushed`) and change nudges all ride it. Message shapes live in `workspace-daemon-protocol`;
`DaemonProtocol.CAPABILITY_VERSION` is what a backend branches on. The path is qits-workspaces'; the
daemon dials the url it was handed verbatim and parses no path out of it.

When the container has a commissioned IdP client, it exchanges that pair at the injected token URL
for a `qits-workspaces`-audience bearer and sends it on every initial and reconnecting WebSocket
handshake. The four values — the client pair, token URL and audience — are all-or-nothing: an absent
set keeps the clone-alone/local topology anonymous, while a partial set fails closed and retries
rather than silently dropping authentication.

**The HTTP API** — a bearer-authenticated server on `127.0.0.1:13338` serving the agent worktrees and
their files, the commands surface, the coding-agent surface, and the two interactive websockets. It **does not bind** without `qits.workspace-daemon.api-token`: it serves an
untrusted checkout, so it is never served anonymously.

**The reverse tunnel** — how qits reaches that loopback server at all. On an `OpenStream`, the daemon
dials a second outbound WebSocket back to qits-workspaces and pipes it to a fresh TCP connection to
one of its own loopback listeners — `127.0.0.1:13338` unless the message names another. The tunnel
carries bytes, so an HTTP request and a WebSocket upgrade traverse it identically and adding a daemon
endpoint costs nothing on the wire. It also carries the request line verbatim, which is the other half
of why nothing rewrites a path: there is no hop in this chain that *could* rewrite one without parsing
and re-emitting HTTP.

`OpenStream.target` is a **name** (`API`, the only one since the web editor went), never a port. A
port on the wire would hand a container-supplied integer straight to `connect()` — the same thing the
refusal of a non-host-relative `path` exists to prevent, pointed at loopback instead of at the
network — so the host names what it wants and the daemon alone knows where that is. Absent ⇒ `API`,
and `API` is not encoded at all, so an ordinary stream is byte-identical to the frame that shipped
before targets existed. A name the daemon has no listener for is refused and logged.

| | |
|---|---|
| `GET·POST /agent-worktrees`, `GET·DELETE /agent-worktrees/{agentId}`, `POST …/{agentId}/yield`, `/turn`, `/entity`, `/blocked`, `GET …/{agentId}/cleanup-check` | agent worktrees |
| `GET /agent-worktrees/{agentId}/files`, `…/files/content`, `…/detection`, `…/component-map` | one agent's files |
| `GET /commands`, `GET /commands/{id}`, `GET /commands/{id}/log`, `POST /commands/{id}/terminate` | commands |
| `POST /agents/sign-in`, `GET /agents/available`, `GET /agent-sessions`, `GET·POST /agent-plugins`, `POST /prompt-refinements` | coding agents |
| `WS /terminal/commands/{id}`, `WS /chat/commands/{id}` | the interactive half |

`POST /agent-worktrees` is idempotent: it makes the agent's worktrees where they are missing and
starts its harness there unless it runs — resuming the session id it is handed when the session's
files are still on the harness volume. Its `env` (the agent's credential) reaches the harness
process and the agent's pushes, and is never written to disk. `DELETE` refuses while work would be
lost (uncommitted, or commits no remote branch has) and answers the cleanup check; `force=true`
removes anyway.

**Which credential each process carries.** No git process the daemon starts inherits a secret of
the workspace (its token, commissioned client, API token, agent configuration), and every one runs
with hooks and fsmonitor off, because agents share the OS user and the repositories' config. A
fetch carries the workspace's git credential; a push of an agent's branch carries that agent's
`QITS_TOKEN` and nothing else, and only from the worktree the daemon made for that agent. Neither
runs in a repository whose own config carries a key the daemon never writes (`credential.*`,
`url.*`, `http.*`, a changed `remote.origin.url`, …; see `GitConfigGuard`). A harness gets the
workspace's secrets blanked and its own `env` laid over them; its MCP headers carry its own token.
The daemon also sets `QITS_AGENT_ID` and `QITS_WORK_ID` in every harness (qits-1153), so a CLI run
inside it — `qits wait` — can name the agent it speaks for. They are not secret, are also in
`agent.json`, and win over a host-supplied value of the same name.
Removed with the single checkout (qits-1152): the checkout-wide file routes, parent
integration, `POST /agents` and the per-workspace turn/entity/blocked routes, declared actions
(`POST /commands`, `/commands/actions`) and the bootstrap chain.

No `{repoId}/{workspaceId}` prefix in any of those paths: this daemon serves exactly one workspace,
so those segments would be a constant the caller has to get right. The response bodies carry both
ids back.

**The base clone's config is re-read on `SIGHUP`, not watched.** The `frameworks:` hints and the
agent defaults come from `/workspace/base/.config/qits/repository.yml` (legacy
`/workspace/base/.qits-config.yml`), read once after the base clone. The base clone's working tree
is never moved, so this is the wrapper's config as of the clone. After editing it, run
`kill -HUP 1`: docker-init/tini at PID 1 forwards the signal to the daemon, which runs as the same
uid. (`pkill -x qits-workspace-daemon` matches nothing — the kernel truncates `comm` to 15
characters.) A file that no longer parses keeps the last good config, and its warning shows up in
the config view.

**The full contract is `docs/openapi.yml`**, hand-written — there is nothing annotation-shaped here
to generate one from. It covers every route above, every field of every body, and both socket
protocols (under `x-websockets`, since OpenAPI does not model them). It is what a consumer's types
are written from, and what a change to this surface has to update: `OpenApiContractTest` fails if a
route in the dispatch ladder goes undocumented or the document invents one, and every field it names
is asserted as a literal string by the API tests.

The paths above are what the daemon *serves*; they are not the whole URL a caller uses. qits-workspaces
proxies to this API and **forwards the caller's path untouched** — no hop rewrites anything — so the
daemon is told where it is mounted instead, as `qits.workspace-daemon.api-base-path`
(`QITS_WORKSPACE_DAEMON_API_BASE_PATH`, injected as `/workspaces/container/{workspaceId}/`). With a
base configured, `GET /agent-worktrees` is served at `GET /workspaces/container/12/agent-worktrees`
and *only* there.

Told, never derived. The daemon matches no shape and strips no leading segment — the same property
the control-socket url has, handed over whole and dialled verbatim. A hop that rewrote the path
would leave the two ends disagreeing about this daemon's own address, and that disagreement surfaces
far from the rewrite that caused it. `WorkspaceApi.route` is the one place the base is known; every
route below it is written as the path it is.

The default is empty — no base, paths served as listed — which is what every direct caller gets, and
is why a bare daemon behaves exactly as it did before a base existed.

These paths are **not** under the `/<segment>/…` convention the six services adopted, and that is
deliberate. Each of those six took its own gateway segment and serves the prefixed path itself. The
daemon is one process per workspace container rather than a single service behind a segment, so its
addressing was a different question — now settled:

**The daemon is never a gateway route, and it has no address on `qits-net` at all.** It has nothing
stable to configure: one process per container, living for one container lifetime. qits-workspaces
owns the workspace row and the container lifecycle, so it proxies at
`/workspaces/container/{workspaceId}/**`, injects the bearer, and asks for a stream over the control
socket the daemon already holds open. Nothing else may reach a daemon.

That closes `migration-plan.md` §9 item 16 (no route, no token injected — so the server did not bind)
and item 21's first half.

## What the daemon does not keep

**Nothing but the volumes outlives the container.** There is no datasource, no Hibernate, no
Panache, no Flyway. The command list, the command logs, the agent-session index and the transcript
aggregates are all in-memory maps, and a container recreate starts them empty. What survives is on
disk: the base clone and every agent worktree on `/workspace`, each agent's `agent.json` (work item,
wrapper branch, harness, session id — never its credential), and the harness's session files on
`/claude-home`.

This is the deliberate scope of moving commands and agents inside, and it costs:

- the Commands list and its logs are **empty** for a workspace whose container was recreated;
- forking, or resuming any other session, from a previous container is **refused** — it fails
  closed, because the store that would vouch for the session did not survive. The one exception is
  an agent's own session: the host hands its id back with `POST /agent-worktrees`, and the agent
  resumes it when its files are still there (qits-1152);
- token and message-count aggregates are lost.

Transcripts themselves are safe: the harness writes them under `/claude-home`, a volume shared
across workspaces that outlives any container. What became ephemeral is the *index* of them.

Two bounds exist because heap here is a container sized for the workspace's own build, not a host
with a disk: `CommandStore.MAX_COMMANDS` (200 finished commands, oldest evicted, running commands
never evicted) and `CommandLogBuffer.DEFAULT_CAPACITY` (50,000 lines per command).

## What it does not carry, and why

**No Jackson.** JSON is `io.vertx.core.json`, which is already in the image via `quarkus-vertx` and
pulls only the streaming core. A second JSON stack would mean databind reflection to register.
`qits-coding-agents` ports three Jackson-heavy readers onto it through its own `json/Json`, a
read-only view with Jackson's missing-node semantics — see that class's javadoc for why a direct
translation would not have been safe.

**No pty4j.** It is JNA plus per-platform `.so` files extracted at runtime, which is the worst case
for a native image. `ForeignPty` calls libc through `java.lang.foreign` instead.

Its downcalls are registered by hand, in two files that ride inside the `qits-commands` jar at
`META-INF/native-image/eu.wohlben/qits-commands/` (they live in the harness library's repository
now, beside the class they are about):

| | |
|---|---|
| `reachability-metadata.json` | a `foreign.downcalls` entry per distinct descriptor |
| `native-image.properties` | `--initialize-at-run-time` for `ForeignPty` |

**Both are needed, and they fix different things.** Without the metadata the image builds and the
binary dies on first PTY use with `MissingForeignRegistrationError`. Without the run-time
initialization the build itself aborts in points-to analysis on `linkToNative`, because Quarkus
initializes application classes in the builder and a `MethodHandle` built there cannot be lowered.
Registering the descriptors does *not* rescue that case — with the stubs registered and the class
still initialized at build time, the build fails identically.

This paragraph used to say that a `static final` `FunctionDescriptor` was enough for GraalVM to
register the stubs automatically, with no `Feature` and no config file. It is not, and never was:
GraalVM registers automatically only where it can constant-fold the descriptor at the
`Linker.downcallHandle` call site, and a `static final` field is not constant to the builder unless
its holder was initialized during the build. The descriptors are still constants, for legibility,
but that alone buys nothing. **If you add a downcall whose shape is not already listed, add it to
the metadata** — the build stays green either way and only the running binary will tell you.

Two traps worth keeping written down. The failure is reported in the *middle* of the `native-image`
output, so a `| tail` shows only Maven's own stack trace, which says nothing. And the per-entry
`reason` field is in the published metadata schema but is rejected by the 25.0.2 parser
(`Unknown attribute(s) [reason] in foreign call`), which is why the explanation there is one
top-level `comment`.

**No JAX-RS.** `WorkspaceApi` routes with a `switch` over `request.path()` on a raw vertx
`HttpServer`.

## What the daemon dials out to

Four different services, on four hosts on `qits-net`. Every address follows
`/<segment>/(api|mcp|git|daemon)/…`, served by the owning service verbatim — through the gateway
*and* when a container is dialled directly, so the prefix is never something to strip on the inside.

| What | Path | Served by | Where the host comes from |
|---|---|---|---|
| the control socket | `/workspaces/daemon/{workspaceId}` | qits-workspaces | `qits.workspace-daemon.url`, injected — the whole url, dialled verbatim |
| the self-clone origin | `/git/{projectId}/{repoName}` (or `/git/{repoId}` with the scope absent) | qits-githost | `qits.workspace-daemon.git-base-url`, injected — **no fallback** |
| MCP `repository` | `/projects/mcp` | qits-projects | `qits.repository-mcp.url` if set, else **derived** |
| MCP `observability` | `/observability/mcp` | qits-observability | `qits.observability-mcp.url` if set, else **derived** |
| MCP `actions` | — | nobody | `qits.actions-mcp.url` only; unset ⇒ an ACTIONS launch fails saying so |
| MCP `qits` | `/mcp` | qits-platform-access-mcp-service | `qits.platform-mcp.url` only; unset ⇒ `WorkspaceMcpServers` omits it rather than failing the launch (qits-630) |
| MCP `browser` | stdio, in the container | the image's `qits-browser-mcp` (Playwright MCP on the base's Chromium) | always attached to Claude launches, every tool pre-approved; Kimi takes none (its ACP session carries url servers only) |

**"Derived" means the authority of the control-socket url, and that is an assumption.** It is only
right where one authority routes every segment — i.e. where the daemon was handed the gateway. So
each derivation announces itself as a `WARN` at agent-wiring time. Nothing here silently invents a
host and then fails as a 404 nobody sees.

**The git base is no longer among them.** It used to derive `<control-socket authority>/artifacts/git`
with a `WARN`. That named a pre-split host which serves no git at all, so the derivation could only
turn a missing setting into a connection error against the wrong service. Unset now fails the
provision with a `DaemonLog` `WARN` naming the key.

What is still unsettled is which host serves each segment. The websocket question that used to sit
beside it is settled: the gateway forwards an upgrade through `EdgeHeaders.applyToUpgrade`, one path
for every socket rather than one per route (see qits-gateway's `AGENTS.md`). So the topology is not
decided here — only made visible and overridable.

## Configuration

Everything is `qits.workspace-daemon.*`, injected per container as `QITS_WORKSPACE_DAEMON_*`. The
identity values (`workspace-id`, `repository-id`, `project-id`, `repo-name`) are
`Optional<String>` deliberately: SmallRye treats an empty default as "no value" and fails to resolve
a plain `String` when the env is absent.

`ControlSocket` is the **single reader** of every setting the capability modules need, and hands them
in as constructor arguments. That is load-bearing for `hooks-port` and `qits.workspace.claude-mount`
in particular: the hook webhook binds one and the agent launch renders it into every hook `curl`, so
two independent reads that disagree would leave agents running fine and silently never reporting
lineage or activity.

The origin sync has three knobs: `base-sync.interval-ms` (default 60000) is how often the base
clone and every submodule fetch all heads, besides the fetch a `PullBranch` hint asks for;
`auto-push.poll-ms` (default 10000) is how often every agent branch is checked for commits to push,
besides the push each harness hook nudges (coalesced over `auto-push.coalesce-ms`, 500);
`auto-push-enabled` (default `true`) turns pushing off and leaves fetching on. A push that git
rejects as a lock or connection problem is retried (`auto-push.max-attempts`,
`auto-push.backoff-initial-ms`, `auto-push.backoff-max-ms`); a non-fast-forward is never forced.

Removed with qits-1152, because a workspace no longer has a branch, an editor or a bootstrap chain:
`branch`, `parent`, `entity-id`, `entity-blocked`, `entity-title`, `entity-status` (each agent's
facts arrive in its start), `projects`, `editor-enabled`, `editor-port`, `bootstrap-autorun`,
`bootstrap-timeout-ms`, `git-status.coalesce-ms` and `git-status.max-wait-ms`.

## The image

This repository publishes the image a workspace container **runs**, not a binary and not a layer:

    <registry>/<repository>/qits-workspace-daemon

One `docker build -f docker/Dockerfile .` produces it. A Mandrel builder stage native-compiles the
daemon; the final stage is the released toolchain base from
`components/qits-workspaces/qits-workspace-oci` with that binary copied to `/usr/local/bin/qits-workspace-daemon` and set as the entrypoint.

**There is no `latest` and there is no local tag.** The predecessor was a hand-built
`qits/workspace:latest` on one machine: no version, no registry, no pipeline, and deleted by every
platform unwrap. Two coordinates replace it, each owned by one pipeline:

| Coordinate | Pushed by | Means |
|---|---|---|
| `:<sha>` | `.config/qits/ci-event-release-request.yml` | this release request's fold built green |
| `:<calver>` | `.config/qits/ci-event-release.yml` | this version was released |

A consumer pins the CalVer. `ci-event-release.yml` declares
`artifacts: [{ type: docker, name: qits/qits-workspace-daemon }]`, so a release announces one
`SoftwareRelease` that a consumer's own bump pipeline follows.

**The toolchain base is pinned, and qits-maintenance moves the pin.** `docker/Dockerfile`'s first
line is

    ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:<version>

One line, one version token, and no pipeline in this repository touches it. Maintenance inventories
literal `ARG <NAME>=<image>:<tag>` defaults as **docker pins** — this one at `arg:WORKSPACE_BASE`,
dropping the registry host so its name is the internal `qits/workspace-base` — and when
`qits-workspace-oci` publishes a newer toolchain it rewrites the tag on a branch of its own and opens
a **release request** for it, whose release republishes this image on the new base. Nobody is in that
loop.

Until 2026-09-03 the following was a hop file here,
`.config/qits/ci-event-upstream-oci-workspace.yml`: a pipeline watching a `SoftwareRelease`, probing
the registry, `sed`ing the line and force-pushing `maintenance/qits-workspace-oci`. It is deleted —
the same follow is one row in the maintenance inventory now. What survives it is the shape of the
line: maintenance sees a pin only in a *literal* value, so a `$`, a `@`, a `://` or a tag with no
`/` before it turns this line back into an ordinary default that nothing follows.

Local dev overrides the pin rather than editing it:

    docker build -t qits/workspace:native -f docker/Dockerfile \
      --build-arg WORKSPACE_BASE=qits/workspace-base:latest .

## Where the code came from

Extracted from the qits monolith. The command and coding-agent modules were **reimplemented** rather
than history-replayed: they target a different runtime (in-container, no database), so a `git
filter-repo` of the originals would have carried a persistence layer and a `docker exec` transport
that have no meaning here. DTO field names were kept exactly, because they are a wire contract the
SPA consumes unchanged. Both have since left this repository altogether — see "The harness, which is
no longer here".
