# qits-workspace-daemon — working notes

Read `README.md` first: it defines the boundary, the module layout, and what the daemon deliberately
does not keep. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green.** No monorepo, no docker, no prior `mvn install`
elsewhere, no credentials, no network. `./mvnw verify` is the gate. That is why the protocol module
is self-contained, why tests build their own git origins rather than using fixtures, and why nothing
in the suite shells `docker`.

**It compiles to a GraalVM native image.** Every dependency is a decision about image size and about
what the builder has to be told. Before adding one, check whether something already in the image
does the job — `io.vertx.core.json` instead of Jackson, `java.lang.foreign` instead of JNA,
`ProcessBuilder` instead of a process library.

`java.lang.foreign` is cheaper than JNA but it is not free: **an FFM downcall has to be registered
by hand**, in the two files under
`qits-commands/src/main/resources/META-INF/native-image/eu.wohlben/qits-commands/`. Making the
`FunctionDescriptor` a `static final` constant does *not* register it — the README's "No pty4j"
paragraph has the whole story, and got it wrong for a while. Add a downcall whose shape is not
already listed and the build stays green; only the running binary will tell you.

**An empty `defaultValue` is not a default.** SmallRye reads `@ConfigProperty(name = "…",
defaultValue = "")` as *no value* and then fails to resolve a plain `String`, so the binary dies at
startup with `Failed to load config value of type class java.lang.String for: <key>`. Optional
settings are `Optional<String>` — the identity values already are, and `api-base-path` had to
become one. **The suite cannot see this**: tests construct `WorkspaceApi` and friends directly and
never resolve config at all. `docker run` on the image with no environment is what catches it, which
is one reason `docker/Dockerfile` is worth having here.

## Module conventions

`eu.wohlben.qits.workspacedaemon.*`, one sub-package per module, no split packages.

The capability modules (`protocol`, `files`, `detection`, `commands`, `agents`) are **framework-free**:
plain classes with plain constructors and no annotations. `ControlSocket` news them up and hands them
to `WorkspaceApi`. Only `ControlSocket`, `WorkspaceApi` and `Main` are CDI beans.

That has a consequence worth stating plainly: **a capability module cannot read configuration.** Every
setting it needs is a `@ConfigProperty` on `ControlSocket` and arrives as a constructor argument. Do
not reach for `ConfigProvider` to get around this — the single-reader property is what stops two
components from resolving the same key differently.

Dependencies run one way: `agents` → `commands` → nothing. A coding agent *is* a command. If you find
yourself wanting `commands` to know about `agents`, you want a seam instead: `commands` declares the
interface (`ChatProtocol`, `ChatProtocolFactory`, `ChatWire`, `CommandChangeListener`) and `agents`
implements it.

## Testing

Plain JUnit 5. **No Mockito, no `@QuarkusTest`** — neither is used anywhere in this repo and neither
should start being. Fakes are anonymous classes or nested records implementing a seam interface; see
`CommandServiceTest.DeclaredActions` or `ContainerLocalProbesTest.Runner`.

Real processes and real sockets are preferred over seams where the thing under test *is* the
integration: `CommandRegistryTest` spawns actual PTYs, `CommandsApiTest` and `AgentsApiTest` bind a
real Vert.x server on an ephemeral port, `CommandSocketsTest` drives a real websocket against a real
`cat`. Anything OS-dependent is `@EnabledOnOs(OS.LINUX)`.

Test names are sentences describing the behaviour, not the method
(`resumeOfASessionFromAPreviousContainerIsRefused`, `aPartialLineIsHeldUntilItsNewlineArrives`).

### Response field names are a wire contract, not a naming choice

`CommandJson` and `AgentJson` keys deserialize into the host's existing DTO records, which the SPA
consumes unchanged. A renamed key is a broken view that **nothing in this reactor would notice**.

So the API tests assert them as **literal strings**. A test that read the names off the records would
rename itself along with the bug. Keep it that way.

## Adding a control-socket message

1. A record in `workspace-daemon-protocol`, added to the `DaemonMessage` permits list.
2. `Type` and `Field` constants — never a bare string at a call site.
3. Encode and decode arms in `DaemonCodec`.
4. A round-trip case in `DaemonCodecTest`.
5. Bump `DaemonProtocol.CAPABILITY_VERSION`.
6. **Mirror the whole module into qits-workspaces**, byte-identical, and handle the new case in its
   `WorkspaceDaemonRegistry.onMessage`. `DaemonCodecTest` living in both copies is the drift
   detector; `diff -r` the two `src/` trees before you push.

Prefer extending an existing message to minting a new one. `WorkspaceChanged` carries a topic name
rather than being a `commandsChanged` message precisely so the next thing that needs a nudge costs
nothing.

An older backend drops a frame it cannot decode (`DaemonControlSocket` catches and logs), so adding
a daemon→qits message degrades safely. The reverse is not true: a message qits sends that an older
daemon image does not know will not be handled at all.

## Addresses: what is injected, what is derived, what is still assumed

`README.md`'s "What the daemon dials out to" has the table. The convention it follows is
`/<segment>/(api|mcp|git|daemon)/…`, and the segment is **served by the owning service**, not added
by the gateway — so it belongs in the url whether you reach the service through the gateway or dial
its container on `qits-net`. There is no unprefixed form to fall back to.

The rule when you add or move an address here:

**Never derive one service's address from another service's.** Two classes used to: `httpBaseOf`
took the control-socket url apart and appended `/mcp/<server>`, and `gitBase` did the same for
`/git`. That was right in the monolith, where one process served everything. It is wrong now — MCP is
qits-projects and qits-observability, git is qits-artifacts, the control socket is qits-workspaces —
and it was wrong *before* the paths changed. The paths only made it visible.

Both derivations survive as **fallbacks**, because the container is still handed exactly one address
and nobody has decided whether that address is the gateway. What is still open there is a gateway
*capability* question rather than a naming one — how websockets pass through it with
`SameOriginUpgradeCheck` still seeing a real `Origin`/`Host`, wanted as one answer for all sockets
rather than one per route — so inventing the topology here would be guessing. What is not allowed is
deriving one **silently**:

- an explicit key exists for every derived address — `qits.workspace-daemon.git-base-url`,
  `qits.repository-mcp.url`, `qits.observability-mcp.url` — and wins outright when set;
- taking the fallback says so, at the point it is taken: a `DaemonLog` `WARN` from `Provisioner`,
  a logged `WARN` from `DaemonMcpEndpoints`;
- an address with no derivable form at all **throws** rather than being made up.
  `qits.actions-mcp.url` is that case: no service in the split serves the `actions` MCP server
  (`migration-plan.md` §9 item 6), so an ACTIONS-scope launch fails with that sentence instead of
  handing the agent a URL that 404s.

That last point is the shape of the whole rule. A wrong address here fails as a connection error or
a 404, and a 404 from an MCP server surfaces to the user as *a tool that isn't there* — the launch
looks like it worked. Failing loudly at the boundary is the only place it is legible.

**The open question, stated so it is not lost:** none of the three non-control-socket hosts is
injected today. qits-workspaces' `WorkspaceContainerFactory` sets fourteen `QITS_WORKSPACE_DAEMON_*`
vars and none of them names qits-artifacts, qits-projects or qits-observability. So in a
host-created container all three fall back to the control socket's authority, and the daemon is
running on the assumption that one authority routes every segment. That is either correct (the url
points at the gateway) or it is a bug the WARNs will name. **Whoever settles the gateway question
settles this**: either the host injects the three keys, or it injects a gateway url and the
derivations become sound. Do not quietly pick one by adding a default.

**The MCP server names are a cross-repo contract.** `repository` is qits-projects; `observability`
is qits-observability, renamed from `repository` for the reason that both services declaring that
name meant this daemon could only ever address one of them. The telemetry allowlist entries moved
with it (`mcp__observability__telemetry*`); under the old names they allowlisted tools no reachable
server declared. If you touch `AgentLaunchService.serversFor`, the tool-name prefix and the server
key have to move together.

## Things that look wrong and are not

**`setsid --ctty` for terminals.** On the host this would have failed with EPERM — `docker exec -it`
had already made the shell a session leader owning the inner TTY. In the container nothing has
claimed the terminal yet and the child *must* claim it, or `test -t 1` fails and every full-screen
TUI drops to line mode. The chat path keeps `setsid -w` for the opposite reason: without `-w` the
parent double-forks and exits, the pipes tear down, and a chat reads EOF before its first turn.

**The pid file.** `echo $$ > /tmp/qits-cmd-<id>.pid` plus `kill -- -pgid` looks like docker-era
residue and is not: a compound script's children are only reachable through the process group. The
contents are validated as digits-only before being interpolated into a shell line, because the script
that wrote it runs from an untrusted checkout.

**`KimiCodeAgent.start()` does not `exec`.** The symlink-farm prelude installs an `EXIT` trap to clean
up its temp home; `exec` would replace the shell and the trap would never fire.

**`Json.parse` never throws.** That is what lets the ported transcript readers drop their try/catch —
a truncated tail line or a stray write in a JSONL file just reads as absent. See the class javadoc for
the one deliberate divergence from Jackson.

## Untrusted input

The checkout is untrusted: an agent writes to it, and `.qits-config.yml` comes from it. The hook
webhook is unauthenticated (loopback-only, but so is anything the checkout can run).

So: session ids are validated before becoming transcript filenames, hook-reported transcript paths are
normalized and required to sit under the harness config dir, plugin ids are pattern-matched before
reaching a shell, and pid-file contents are digits-only. When you add a value that crosses from the
checkout or a hook into a path or an argv, validate it at the boundary and say why in a comment.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why* — the tradeoff, the
alternative rejected, the failure it prevents. What the code does is the code's job.
