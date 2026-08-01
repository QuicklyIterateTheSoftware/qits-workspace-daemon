# qits-workspace-daemon as a GraalVM-native container IMAGE — the same shape as qits-gateway's
# docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal runtime stage carries
# only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `./mvnw package
# -Dnative` produces `workspace-daemon/target/qits-workspace-daemon` with whatever GraalVM is on
# PATH. What this adds is the image around it — and a way for a machine with no GraalVM to get a
# binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/workspace-daemon:latest -f docker/Dockerfile .
#
# --- what this image is FOR, because it is not a workspace container ------------------------------
# A workspace container is this binary running as PID 1 inside a toolchain image — debian plus node,
# maven, a JDK and a native toolchain, because the daemon's whole job is to build and run the user's
# checkout. That toolchain is the qits monolith's `workspace-base` stage and is shared with its app
# image, so it is not this repo's to own and could not be built here: a clone of this repo alone
# builds and tests green with no monorepo and no docker, which is the rule that shape protects.
#
# So this image is the DISTRIBUTION of the binary, and the two consumers take it differently:
#
#   the monolith    replaces its `workspace-daemon-build` stage with
#                     COPY --from=qits/workspace-daemon:latest \
#                          /work/qits-workspace-daemon /usr/local/bin/qits-workspace-daemon
#                   Today that stage compiles the monolith's OWN pre-extraction `workspace-daemon`
#                   module — no qits-commands, no PTY, no reverse tunnel — so `qits/workspace:latest`
#                   ships a daemon several capability versions behind this one. Pointing it here is
#                   what makes a released workspace image carry this code.
#
#   a test          layers the binary onto an existing toolchain image; see Dockerfile.workspace
#                   next to this file, which is what DaemonApiGateIT and the full-chain harness run
#                   against.
#
# Running this image directly is legitimate but idle: with no QITS_WORKSPACE_DAEMON_URL it logs
# "no url configured — workspace-daemon is idle" and stays up. There is no checkout here to serve.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8081/quay/…` is qits-artifacts' pull-through mirror of quay.io: the first build fetches
# the image from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
# verify` on a clone is the gate for those.
RUN ./mvnw -B -ntp -pl workspace-daemon -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
# `localhost:8081/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8081/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-workspace-daemon` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters more here than elsewhere, since this
# is PID 1 of a container a user is working inside.
COPY --from=build --chown=1001:root /src/workspace-daemon/target/qits-workspace-daemon /work/qits-workspace-daemon
RUN chmod +x /work/qits-workspace-daemon

# NOT exposed, and that is the point: WorkspaceApi binds 127.0.0.1 and is reached over the reverse
# tunnel it dials out for. A peer container on qits-net gets a connection refused by the network
# stack rather than by a token check. See README.md, "The two channels".
USER 1001

ENTRYPOINT ["/work/qits-workspace-daemon"]
