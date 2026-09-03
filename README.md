# qits-workspace-daemon

Everything qits runs **inside** a workspace container. One binary, one workspace, one container
lifetime.

A workspace is a branch ref in a repository's bare origin plus a container that clones that branch
into `/workspace`. This binary is that container's PID-1 child. It provisions the checkout, keeps it
synced with its origin, supervises the repository's dev servers, serves the working tree, and runs
the commands and coding agents the user drives from the browser. Everything on the host's side of the
boundary belongs to
[qits-workspaces-service](https://github.com/QuicklyIterateTheSoftware/qits-workspaces-service).

    ./mvnw verify     # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `workspace-daemon-protocol/` | The control-plane wire contract: message records + a codec over a plain `Map`. Depends on nothing. qits-workspaces-service vendors a byte-identical copy. |
| `workspace-daemon-files/` | Reading the checkout: file listing, content, lazy directories, gitignore. |
| `workspace-daemon-detection/` | Framework detection and the component map, over `workspace-daemon-files`. |
| `qits-commands/` | Launching and supervising processes: the PTY, the process registry, chat transports, the in-memory command store and log buffer. |
| `qits-coding-agents/` | The Claude Code and Kimi harnesses: launch rendering, the ACP chat client, transcript import, plugins, auth. Depends on `qits-commands` — a coding agent *is* a command. |
| `workspace-daemon/` | The Quarkus application: the control socket, the HTTP API, the hook webhook, provisioning, git, service and web-editor supervision. Wires every module above by hand. |

The first five are **framework-free**: no Quarkus, no CDI, no JAX-RS, no Jackson. They are plain
jars with plain constructors that `ControlSocket` news up. That is not stylistic — it is what keeps
the GraalVM native image small and free of anything the builder has to be told about by hand.

## The two channels

**The control socket** — the daemon dials `ws://<host>/workspaces/daemon/{workspaceId}` on boot and
keeps it open. Provisioning progress, bootstrap steps, service transitions, working-tree status,
agent activity and change nudges all ride it. Message shapes live in `workspace-daemon-protocol`;
`DaemonProtocol.CAPABILITY_VERSION` is what a backend branches on. The path is qits-workspaces'; the
daemon dials the url it was handed verbatim and parses no path out of it.

When the container has a commissioned IdP client, it exchanges that pair at the injected token URL
for a `qits-workspaces`-audience bearer and sends it on every initial and reconnecting WebSocket
handshake. The four values — the client pair, token URL and audience — are all-or-nothing: an absent
set keeps the clone-alone/local topology anonymous, while a partial set fails closed and retries
rather than silently dropping authentication.

**The HTTP API** — a bearer-authenticated server on `127.0.0.1:13338` serving the working tree, the
commands surface, the coding-agent surface, the service and bootstrap surfaces, and the two
interactive websockets. It **does not bind** without `qits.workspace-daemon.api-token`: it serves an
untrusted checkout, so it is never served anonymously.

**The reverse tunnel** — how qits reaches that loopback server at all. On an `OpenStream`, the daemon
dials a second outbound WebSocket back to qits-workspaces and pipes it to a fresh TCP connection to
one of its own loopback listeners — `127.0.0.1:13338` unless the message names another. The tunnel
carries bytes, so an HTTP request and a WebSocket upgrade traverse it identically and adding a daemon
endpoint costs nothing on the wire. It also carries the request line verbatim, which is the other half
of why nothing rewrites a path: there is no hop in this chain that *could* rewrite one without parsing
and re-emitting HTTP.

`OpenStream.target` is a **name** (`API`, `EDITOR`), never a port. A port on the wire would hand a
container-supplied integer straight to `connect()` — the same thing the refusal of a non-host-relative
`path` exists to prevent, pointed at loopback instead of at the network — so the host names what it
wants and the daemon alone knows where that is. Absent ⇒ `API`, and `API` is not encoded at all, so
an ordinary stream is byte-identical to the frame that shipped before targets existed. A name the
daemon has no listener for is refused and logged, never served by the other one.

**The web editor** — a workspace image that carries openvscode-server at `/opt/openvscode-server` has
it supervised by the daemon, on `127.0.0.1:13339` (`qits.workspace-daemon.editor-port`) and reached
only through the tunnel's `EDITOR` target. Loopback for the API's reason: on `qits-net` an editor is
an unauthenticated shell over someone else's untrusted checkout, and that bind is also what makes
`--without-connection-token` safe — a token would defend a port that does not exist, and sharing it
with the host is the arrangement the tunnel replaced.

The daemon reports the editor's lifecycle as `EditorState` (`STARTING` / `RUNNING` / `ENDED`), once
per control-socket connect and once per transition, which is what the host gates its proxy and splash
on. **A workspace whose image has no editor sends the frame never** — the absence is the capability
announcement, so there is one signal rather than a flag and a state that can disagree, and a plain
workspace behaves exactly as it did before an editor existed.

| | |
|---|---|
| `GET /files`, `/files/content`, `/detection`, `/component-map` | the checkout |
| `POST /fast-forward`, `/update-from-parent` | parent integration |
| `GET·POST /commands`, `GET /commands/actions`, `GET /commands/{id}`, `GET /commands/{id}/log`, `POST /commands/{id}/terminate` | commands |
| `POST /agents`, `GET /agents/available`, `GET /agent-sessions`, `GET·POST /agent-plugins`, `POST /prompt-refinements` | coding agents |
| `GET /services`, `POST /services/{name}/start`, `POST /services/{name}/signal` | service supervision |
| `GET /bootstrap-commands`, `POST /bootstrap-commands/run`, `POST /bootstrap-commands/{name}/run` | the bootstrap chain |
| `WS /terminal/commands/{id}`, `WS /chat/commands/{id}` | the interactive half |

Every write on the last two answers **202**, not 200. Both are long-running — a bootstrap step is
bounded only by `bootstrap-timeout-ms`, an hour by default — and both already report themselves on
the control socket, as `ServiceTransition`s and as the `BootstrapStep`/`BootstrapOutcome`/
`Bootstrapped` sequence. Answering with a second, synchronous account of an outcome the caller is
already subscribed to would be two sources of one truth.

No `{repoId}/{workspaceId}` prefix in any of those paths: this daemon serves exactly one workspace,
so those segments would be a constant the caller has to get right. The response bodies carry both
ids back.

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
base configured, `GET /files` is served at `GET /workspaces/container/12/files` and *only* there.

Told, never derived. The daemon matches no shape and strips no leading segment — the same property
the control-socket url has, handed over whole and dialled verbatim. A hop that rewrote the path
would leave the two ends disagreeing about this daemon's own address, and that disagreement surfaces
far from the rewrite that caused it. `WorkspaceApi.route` is the one place the base is known; every
route below it is written as the path it is.

The default is empty — no base, paths served as listed — which is what every direct caller gets, and
is why a bare daemon behaves exactly as it did before a base existed.

The same arrangement covers the services the daemon spawns, one hop further out: qits-workspaces
injects `QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE` (`/workspaces/service/{workspaceId}`, the prefix
its verbatim service proxy answers under), and the daemon completes it per spawn with the declared
service id and `web-view.base-path`, baking the result into the dev server's environment as
`QITS_PUBLIC_BASE`. The dev server serving under that base is the whole web-view contract — the
proxy rewrites nothing, so an app that never learned its base 404s the framed view. With no proxy
base injected, a web-viewable spawn warns and leaves `QITS_PUBLIC_BASE` unset rather than guessing.

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
and item 21's first half. The `services` and `bootstrap-commands` routes above came back here for the
same reason: their host-side routes were deleted when the conventions landed, so the capability had
stayed and only the addressability was missing.

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
for a native image. `ForeignPty` calls libc through `java.lang.foreign` instead.

Its downcalls are registered by hand, in two files under
`qits-commands/src/main/resources/META-INF/native-image/eu.wohlben/qits-commands/`:

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
identity values (`workspace-id`, `repository-id`, `branch`, `parent`, `project-id`, `repo-name`) are
`Optional<String>` deliberately: SmallRye treats an empty default as "no value" and fails to resolve
a plain `String` when the env is absent.

`ControlSocket` is the **single reader** of every setting the capability modules need, and hands them
in as constructor arguments. That is load-bearing for `hooks-port` and `qits.workspace.claude-mount`
in particular: the hook webhook binds one and the agent launch renders it into every hook `curl`, so
two independent reads that disagree would leave agents running fine and silently never reporting
lineage or activity.

`editor-enabled` (default `false`) and `editor-port` (default `13339`) are the web editor's pair, and
the default is the contract for every image without one: nothing spawned, nothing announced, and the
tunnel's `EDITOR` target refused. The switch alone does not conjure a binary — supervision also
requires `/opt/openvscode-server/bin/openvscode-server` to be there — so an image that sets it and
does not carry the editor is silent on the wire and says so in the container log.

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
| `:<sha>` | `.config/qits/ci-post-receive.yml` | this commit built green |
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
`qits-workspace-oci` publishes a newer toolchain it rewrites the tag on a branch of its own and asks
the release door, whose release republishes this image on the new base. Nobody is in that loop.

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

Extracted from the qits monolith. `qits-commands` and `qits-coding-agents` were **reimplemented**
rather than history-replayed: they target a different runtime (in-container, no database), so a
`git filter-repo` of the originals would have carried a persistence layer and a `docker exec`
transport that have no meaning here. DTO field names were kept exactly, because they are a wire
contract the SPA consumes unchanged.
