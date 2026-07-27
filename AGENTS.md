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
