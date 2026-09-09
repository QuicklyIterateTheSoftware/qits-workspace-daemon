package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.agents.AgentMcpIds;
import eu.wohlben.qits.agents.AgentMcpNarrowing;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentMcpServers;
import eu.wohlben.qits.agents.McpEndpoints;
import eu.wohlben.qits.agents.ScopedMcp;
import eu.wohlben.qits.commands.InvalidCommandRequestException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Which MCP servers a workspace session attaches, how each is narrowed, and what is pre-approved on
 * it — this daemon's half of the {@link AgentMcpServers} seam.
 *
 * <p><b>It is a move, not a rewrite.</b> Every list and every url below was a private constant and a
 * {@code serversFor} switch inside this daemon's own copy of {@code AgentLaunchService}. When the
 * two copies of that class became one library, the mapping could not come with it: the projects
 * daemon attaches <em>one</em> server ({@code repository}, because a project agent's job is the
 * plan) and this one attaches <em>three</em> across three services, with different narrowing, a
 * different pre-approval list and a different order. Neither is a default the other could fall back
 * on, so the mapping stayed with its daemon and the library renders whatever it is handed.
 *
 * <p>The pre-approval lists travelled for the same reason. They are not a property of the harness;
 * they are a policy about what <em>this</em> product's agents may do without asking. The order is
 * load-bearing — it is rendered into one {@code --allowedTools} argument that the library's own
 * suite asserts byte for byte against a fixture copy of this file.
 *
 * <p>{@code repository} and {@code observability} are two servers on two services (qits-projects and
 * qits-observability), not one server with two halves. They are listed together wherever the session
 * is narrowed to a workspace, because that is the pairing the old single {@code repository} server
 * presented.
 */
final class WorkspaceMcpServers implements AgentMcpServers {

  /**
   * The read-only tools of the {@code actions} MCP server, pre-approved so the session can
   * list/inspect actions without a permission prompt. The mutating tools are left out so the agent
   * still prompts before changing anything. Names are the agent's MCP tool ids: {@code
   * mcp__<server>__<tool>}.
   */
  private static final List<String> READ_ONLY_ACTION_TOOLS =
      List.of(
          "mcp__actions__listGlobalActions",
          "mcp__actions__getGlobalAction",
          "mcp__actions__listRepositoryActions",
          "mcp__actions__getRepositoryAction");

  /**
   * The read-only tools of the {@code repository} MCP server, pre-approved the same way.
   *
   * <p>{@code list_tickets} and {@code get_ticket} sit here at the same standing as the navigation
   * reads: surveying the tickets on a repository is how a session finds out what it was sent to do,
   * and {@code get_ticket} returns the whole comment thread with the ticket, so one call is the
   * reading half of the ticket domain. Neither changes anything.
   *
   * <p>{@code list_epics} and {@code get_epic} sit here for the same reason and at the same
   * standing. Surveying the project's plan is how a dispatched session finds out what it was sent to
   * do, and {@code get_epic} returns the whole feature/task tree in one call, so it is the reading
   * half of the epic domain. Neither changes anything. Leaving them out would not merely cost a
   * prompt: qits-projects' "Start implementation" dispatch composes a first turn that tells the
   * agent to read its epic with {@code get_epic}, and on the kimi path {@code enabledTools} is the
   * session's whole tool surface rather than a pre-approval, so that instruction would be
   * unreachable rather than prompted.
   *
   * <p>The membership and the <em>order</em> both differ from the projects daemon's list of the same
   * name — {@code listWorkspaces} is here and the epic and ticket pairs are the other way round —
   * which is precisely why one merged list could not have been the union, and why this file is a
   * daemon's rather than the shared library's: the list is rendered into one {@code --allowedTools}
   * argument the suites assert as a literal, so merging would have moved a byte on one side.
   */
  private static final List<String> READ_ONLY_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listWorkspaces",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt",
          "mcp__repository__list_tickets",
          "mcp__repository__get_ticket",
          "mcp__repository__list_epics",
          "mcp__repository__get_epic");

  /**
   * The two ticket-thread writes of the {@code repository} MCP server — the one deliberate exception
   * to "only reads are pre-approved", and kept in its own bucket rather than smuggled into the
   * read-only list above so the exception has to be read to be taken.
   *
   * <p>A workspace agent that investigated or fixed something should be able to say so on the
   * ticket's thread without a prompt in the way. Commenting is additive — it appends to a thread
   * rather than changing a ticket's state — and a comment stays editable, so pre-approving the pair
   * costs at worst a wrongly-worded note, never a changed plan. Autonomous runs lose both anyway:
   * the {@code agentReadOnly=true} marker puts qits-projects' own tool filter in front of every
   * mutating tool, and this list cannot buy past it. For kimi the bucket is not a convenience at all
   * — {@code enabledTools} is a hard set there, so a tool left out of it does not exist for the
   * session, and without these two the thread is unreachable rather than prompted.
   *
   * <p>What is deliberately absent is the filing half of the domain: {@code create_ticket} and
   * {@code update_ticket} stay unlisted like every other write on every server here. Filing a ticket
   * and editing somebody else's are plan-changing acts, and they stay a prompted act for Claude and
   * out of reach for kimi; the projects-daemon front desk is the filing surface.
   */
  private static final List<String> TICKET_THREAD_TOOLS =
      List.of("mcp__repository__add_ticket_comment", "mcp__repository__update_ticket_comment");

  /**
   * {@code transition_ticket} — the second named exception, and it exists because of one caller.
   *
   * <p>qits-projects' "Assign agent" dispatches an agent onto a ticket and its first turn (composed
   * by {@code TicketDispatchController.instruction}) ends by telling the agent to resolve that
   * ticket once its changes are released. Nothing else on this platform asks a workspace agent to
   * move a ticket's status, and without the tool here that sentence is an instruction the session
   * cannot carry out: on the kimi path {@code enabledTools} is the session's whole tool surface, so
   * an unlisted tool does not exist rather than being prompted for.
   *
   * <p>Its own bucket, not appended to {@link #TICKET_THREAD_TOOLS}, because the two are exceptions
   * for different reasons and the reasons are what a reader has to weigh. Commenting is additive and
   * costs a wrongly-worded note; resolving is a statement about somebody's bug that people act on.
   * What makes it acceptable is that it is <em>reversible through the same door</em> — reopening is
   * the same tool — so the worst case is a status a person flips back, and that the dispatch is a
   * person pressing a button on a specific ticket rather than an agent choosing a ticket to close.
   *
   * <p>The fence that matters is unchanged and is not this list. An <b>autonomous</b> run carries the
   * {@code agentReadOnly=true} marker, and qits-projects' {@code ReadOnlyRepositoryToolFilter} hides
   * all five ticket writes behind it.
   */
  private static final List<String> TICKET_RESOLUTION_TOOLS =
      List.of("mcp__repository__transition_ticket");

  /**
   * {@code mark_task_implemented} — the third named exception, and like the second it exists because
   * of one caller.
   *
   * <p>qits-projects' "Start implementation" stands a workspace on {@code epic/<slug>} and dispatches
   * an agent into it, and the first turn it composes ({@code EpicDispatchController.instruction})
   * tells that agent to mark each task implemented as the work lands. Nothing else asks a workspace
   * agent to touch an epic's tasks, and on the kimi path {@code enabledTools} is the session's whole
   * tool surface, so an unlisted tool is a dead letter rather than a prompt.
   *
   * <p>What makes pre-approving a <em>write</em> acceptable here is narrow and worth stating. It
   * records a fact about work the agent itself just did, so the agent is the authority on it rather
   * than a party guessing at somebody else's state. qits-projects accepts it only while the owning
   * epic is in IMPLEMENTATION ({@code EpicLifecycle.requireImplementation}), so it is reachable
   * exactly during the dispatch it was added for. And it changes a <em>marker</em>, not a plan: by
   * then the epic's scope is frozen, and this tool cannot add, remove or reword a feature or a task
   * — only say that one of them landed.
   *
   * <p>It is an interim. qits-projects' own note on the tool says merge-derived markers are the
   * intended answer, and when they arrive this bucket goes with the prompt-driven step.
   */
  private static final List<String> TASK_IMPLEMENTATION_TOOLS =
      List.of("mcp__repository__mark_task_implemented");

  /**
   * The repository server's full pre-approval: its reads, plus the two ticket exceptions and the
   * epic task-marker one.
   */
  private static final List<String> REPOSITORY_TOOLS =
      Stream.of(
              READ_ONLY_REPOSITORY_TOOLS,
              TICKET_THREAD_TOOLS,
              TICKET_RESOLUTION_TOOLS,
              TASK_IMPLEMENTATION_TOOLS)
          .flatMap(List::stream)
          .toList();

  /**
   * The read-only tools of the {@code observability} MCP server — the five telemetry reads.
   *
   * <p>They live on their own server because qits-observability and qits-projects both declared one
   * called {@code repository}, so one MCP url could only ever reach one of them; the telemetry half
   * is {@code observability}, served at {@code /observability/mcp}.
   */
  private static final List<String> READ_ONLY_OBSERVABILITY_TOOLS =
      List.of(
          "mcp__observability__telemetryErrors",
          "mcp__observability__telemetryTrace",
          "mcp__observability__telemetrySlowSpans",
          "mcp__observability__telemetrySearchLogs",
          "mcp__observability__telemetryMetrics");

  private final McpEndpoints endpoints;
  private final String repoId;
  private final String workspaceId;

  WorkspaceMcpServers(McpEndpoints endpoints, String repoId, String workspaceId) {
    this.endpoints = endpoints;
    this.repoId = repoId;
    this.workspaceId = workspaceId;
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    String repo = AgentMcpIds.requireId(repoId, "repository id");
    String projectId = AgentMcpIds.requireId(endpoints.projectId(), "project id");
    // Project-scoped, then narrowed to this one repository so a per-subtree session only sees its
    // own repo, not its siblings in the project.
    ScopedMcp narrowedRepositoryServer =
        new ScopedMcp(
            "repository",
            endpoints.mcpUrl("repository")
                + "?projectId="
                + projectId
                + "&repositoryId="
                + repo
                + "&workspaceId="
                + workspaceId,
            REPOSITORY_TOOLS);
    // Telemetry is bucketed per workspace, and qits-observability's tool filter hides the tools
    // outright unless both narrowings are present — so this server is only worth listing where they
    // are, and carries exactly the two scopes that service reads (no projectId: it has no notion of
    // one).
    ScopedMcp observabilityServer =
        new ScopedMcp(
            "observability",
            endpoints.mcpUrl("observability")
                + "?repositoryId="
                + repo
                + "&workspaceId="
                + workspaceId,
            READ_ONLY_OBSERVABILITY_TOOLS);
    return switch (scope) {
      case ACTIONS ->
          // The "configure this repository" session: the actions server for the action library,
          // plus the (narrowed) repository server for the repository reads (branches, workspaces,
          // commits) — the session needs both to configure the repository fully.
          List.of(
              new ScopedMcp(
                  "actions",
                  endpoints.mcpUrl("actions") + "?repositoryId=" + repo,
                  READ_ONLY_ACTION_TOOLS),
              narrowedRepositoryServer,
              observabilityServer);
      case REPOSITORY -> List.of(narrowedRepositoryServer, observabilityServer);
      case PROJECT ->
          // Project scope only, no repository narrowing — the session sees every repository in the
          // project. It still runs in this repository's workspace (the terminal needs a checkout).
          // No observability server: without the repository/workspace narrowing its tools are
          // filtered away at the far end, so offering it would advertise a dead end.
          List.of(
              new ScopedMcp(
                  "repository",
                  endpoints.mcpUrl("repository") + "?projectId=" + projectId,
                  REPOSITORY_TOOLS));
    };
  }

  /**
   * One server by key, narrowed exactly as a surface's configuration asks — the seam the agent
   * configuration epic added, <b>implemented</b> here rather than left on the library's default.
   *
   * <p>The default implementation ignores the narrowing and answers {@link #serversFor}'s own url,
   * which is a dated crutch for a daemon that has not adopted the seam: it renders what it always
   * rendered, {@link #honoursNarrowing()} stays false, and every launch that attaches a server
   * records a note saying its addressing was the host's rather than the document's. Implementing it
   * is what turns the editor's three narrowing checkboxes from a description of what this daemon
   * already does into something an operator can actually change.
   *
   * <p>Four rules, all of them from the seam's javadoc and all of them load-bearing:
   *
   * <ul>
   *   <li><b>Canonical parameter order</b> — {@code projectId}, {@code repositoryId}, {@code
   *       workspaceId}. That is the order {@link #serversFor} builds its urls in, and the rendered
   *       command line is asserted as a literal on both harnesses, so the order is contract rather
   *       than detail.
   *   <li><b>Every interpolated id validated</b> with {@link AgentMcpIds#requireId}. The url ends up
   *       inside a single-quoted shell argument and the renderer does no escaping of its own. Note
   *       this is <em>stricter</em> than {@link #serversFor}, which validates the project and
   *       repository ids and passes the workspace id through: that asymmetry is deliberately not
   *       fixed there, because those urls are asserted byte for byte and tightening them would move
   *       a refusal into a path this cutover promised not to change.
   *   <li><b>A narrowing this daemon cannot satisfy is refused</b>, never dropped. {@code
   *       observability} and {@code actions} have no notion of a project — qits-observability buckets
   *       telemetry by repository and workspace, and the actions server addresses a repository — so a
   *       document asking either to carry {@code projectId} is a document describing something that
   *       does not exist. Dropping the parameter would answer a broader url than was asked for, which
   *       is the one failure a narrowing exists to prevent.
   *   <li><b>A key this daemon does not serve answers empty</b>, and the launch turns that into its
   *       own refusal naming the surface.
   * </ul>
   *
   * <p><b>Scope is not consulted here, and that is the point.</b> {@link #serversFor} uses the scope
   * to decide both which servers attach and how narrow they are, because it is the only signal it
   * has. Once a document names the servers and the narrowing outright, the scope has nothing left to
   * decide: {@code observability} is omitted at {@link AgentMcpScope#PROJECT} there only because the
   * scope carries no repository or workspace to narrow it with, and a document that asks for that
   * narrowing has said what the scope could not. Refusing it on the scope's say-so would make the
   * checkbox a lie in the other direction.
   */
  @Override
  public Optional<ScopedMcp> serverFor(
      String key, AgentMcpScope scope, AgentMcpNarrowing narrowing) {
    return switch (key) {
      case "repository" ->
          Optional.of(
              new ScopedMcp("repository", narrowedUrl("repository", narrowing), REPOSITORY_TOOLS));
      case "observability" ->
          Optional.of(
              new ScopedMcp(
                  "observability",
                  narrowedUrl("observability", refuseProject(narrowing, "observability")),
                  READ_ONLY_OBSERVABILITY_TOOLS));
      case "actions" ->
          Optional.of(
              new ScopedMcp(
                  "actions",
                  narrowedUrl("actions", refuseProject(narrowing, "actions")),
                  READ_ONLY_ACTION_TOOLS));
      default -> Optional.empty();
    };
  }

  /**
   * True: {@link #serverFor} builds the url the document asked for. Read by the library to decide
   * whether a launch has to record that its addressing was the host's guess.
   */
  @Override
  public boolean honoursNarrowing() {
    return true;
  }

  /**
   * {@code server}'s base url with exactly the asked-for ids appended, in the canonical order. An
   * empty narrowing yields the bare url — the whole platform, unscoped — which is a legitimate thing
   * for a document to ask for and is what the {@code repository} server serves at project scope
   * minus its {@code projectId}.
   */
  private String narrowedUrl(String server, AgentMcpNarrowing narrowing) {
    StringBuilder url = new StringBuilder(endpoints.mcpUrl(server));
    char separator = '?';
    if (narrowing.project()) {
      url.append(separator)
          .append("projectId=")
          .append(AgentMcpIds.requireId(endpoints.projectId(), "project id"));
      separator = '&';
    }
    if (narrowing.repository()) {
      url.append(separator)
          .append("repositoryId=")
          .append(AgentMcpIds.requireId(repoId, "repository id"));
      separator = '&';
    }
    if (narrowing.workspace()) {
      url.append(separator)
          .append("workspaceId=")
          .append(AgentMcpIds.requireId(workspaceId, "workspace id"));
    }
    return url.toString();
  }

  /**
   * {@code narrowing} unchanged, or a refusal when it asks a project-blind server to carry a
   * {@code projectId}. Named rather than inlined twice so the reason sits in one place: the
   * parameter would not be ignored by the far end, it would be an unrecognised query parameter on a
   * server that never scoped by project, and the session would look narrowed while being anything
   * but.
   */
  private static AgentMcpNarrowing refuseProject(AgentMcpNarrowing narrowing, String server) {
    if (narrowing.project()) {
      throw new InvalidCommandRequestException(
          "The '"
              + server
              + "' MCP server cannot be narrowed to a project: it has no notion of one. Attach it"
              + " with repository and workspace narrowing, or attach 'repository' instead.");
    }
    return narrowing;
  }
}
