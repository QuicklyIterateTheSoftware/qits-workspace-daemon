# The workspace container image: the released toolchain with this daemon compiled in as PID 1.
#
# ONE BUILD PRODUCES THE IMAGE A WORKSPACE RUNS. There is no second Dockerfile and no intermediate
# "daemon image" any more. Until 2026-08-10 there were two — `docker/Dockerfile` shipped a bare
# binary on ubi-minimal, `docker/Dockerfile.workspace` layered that image onto the toolchain — so the
# runnable image needed two builds, two coordinates and a build argument naming the first from the
# second. The layering was the only thing the second file did, and a `COPY --from` inside one build
# does it with nothing to keep matched.
#
# WHAT THIS IMAGE IS PUBLISHED AS: `<registry>/<repository>/qits-workspace-daemon`, sha-tagged on
# every push and CalVer-tagged on release. See `.config/qits/ci-post-receive.yml` and
# `.config/qits/ci-event-release.yml`. There is no `:latest` — the hand-built `qits/workspace:latest`
# this replaces had no version, no registry and no pipeline, and every platform unwrap deleted it.
#
# --- the base pin ---------------------------------------------------------------------------------
# ONE LINE, ONE VERSION TOKEN, AND A MACHINE EDITS IT. `.config/qits/ci-event-upstream-oci-workspace.yml`
# seds this exact line when images/qits-oci-workspace releases a toolchain, so the anchored prefix
# `ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:` is a contract with that
# file. Move the registry host or the image name here and the bump silently matches nothing — which
# is why that file re-reads the line after its sed and fails when it did not take.
#
# PINNED BY VERSION, NEVER FOLLOWED BY A FLOATING TAG. images/qits-oci-workspace publishes only
# `:<version>` (its own ONE TAG PER PIPELINE rule), so there is no floating tag to follow even if we
# wanted one — and following one would make this image unreproducible: rebuilding a released tag
# would silently pick up a different toolchain.
#
# THE DEFAULT IS THE PIN, WHICH IS WHY OVERRIDING IT IS SAFE. CI passes no `--build-arg`, so a
# release rebuilds on exactly the toolchain its tree was released with. A developer with a
# hand-built base passes one:
#
#     docker build -t qits/workspace:native -f docker/Dockerfile \
#       --build-arg WORKSPACE_BASE=qits/workspace-base:latest .
#
# That is the whole local-dev story. `DaemonApiGateIT` in qits-workspaces self-skips unless the image
# it is given entrypoints to `qits-workspace-daemon`, so this is what makes that gate runnable:
#
#     cd ../../services/qits-workspaces
#     ./mvnw test -pl service -Dtest=DaemonApiGateIT -Dsurefire.failIfNoSpecifiedTests=false \
#       -Dqits.workspace.image=qits/workspace:native
ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:2026.819.175546

# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
#
# All three base references here are the HOST DAEMON's to resolve, not this build's, which is why
# the CI step declares `docker: true` and passes no `--network`: nothing inside the build dials the
# platform. The reactor declares no `<repositories>`, so the compile below resolves from Maven
# Central.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds workspace-daemon's dependencies from source —
# they are 1.0.0-SNAPSHOTs this repo never publishes (no distributionManagement, no <repositories>),
# so a builder container with an empty ~/.m2 has nowhere else to get them. Copying module by module
# would mean editing this file every time one is added, and forgetting to would fail the build with
# an unresolved dependency rather than anything that names the real cause. `.dockerignore` is what
# keeps this cheap — without it this line drags in every target/ dir.
COPY . .

# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# Tests are skipped because they are not skippable HERE: CommandRegistryTest spawns real PTYs and
# the git suites fork real git, both of which want a workspace this stage does not have. `./mvnw
# verify` on a clone is the gate for those, and the CI pipeline runs it before this build so a broken
# test never costs a GraalVM compile.
RUN ./mvnw -B -ntp -pl workspace-daemon -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime: the toolchain, with the daemon as its entrypoint ------------------------------------
# The toolchain is not built here and could not be: reproducing it would put node, maven, a JDK, a
# Chromium and a native toolchain in a repo whose first rule is that a clone alone builds green with
# no monorepo and no docker. It lives in images/qits-oci-workspace and arrives as a released image.
FROM ${WORKSPACE_BASE}

# `quarkus.package.output-name` names the binary `qits-workspace-daemon` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters here more than elsewhere, since this
# is PID 1 of a container a user is working inside.
COPY --from=build /src/workspace-daemon/target/qits-workspace-daemon /usr/local/bin/qits-workspace-daemon
RUN chmod +x /usr/local/bin/qits-workspace-daemon

# NO `USER`, NO `WORKDIR`, NO `EXPOSE`, and each absence is deliberate. The base already sets
# `WORKDIR /workspace` and runs as root, which is what the user's checkout and its build tools need;
# a `USER 1001` here — which the retired bare-binary image did carry — would break every workspace.
# And nothing is exposed because WorkspaceApi binds 127.0.0.1 and is reached over the reverse tunnel
# the daemon dials out for: a peer container on qits-net gets a connection refused from the network
# stack rather than from a token check. See README.md, "The two channels".
#
# No CMD and no `sleep infinity` fallback: a container whose daemon cannot start is not a usable
# workspace, and should fail visibly rather than idle looking healthy.
ENTRYPOINT ["/usr/local/bin/qits-workspace-daemon"]
