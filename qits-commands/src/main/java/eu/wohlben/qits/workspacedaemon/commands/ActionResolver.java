package eu.wohlben.qits.workspacedaemon.commands;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a declared action to the script that runs it.
 *
 * <p>This is where the last cross-context edge went. On the host, {@code CommandService.launch}
 * called {@code featureflow.ActionResolutionService.resolveForRepository} — and {@code
 * domain.featureflow} is monolith-only and deferred (migration-plan.md §3.9, §9 item 6), so a
 * faithful extraction would have had to declare a port and leave it unwired, making {@code POST
 * /commands} permanently unusable in the extracted repo.
 *
 * <p>It did not have to. Actions had already stopped being database rows: {@code DaemonQitsConfig}
 * parses {@code actions:} out of the checkout's own {@code .qits-config.yml} with exactly the
 * fields {@code ResolvedAction} carried — id, name, description, execute, check, interactive,
 * environment. That is the V42–V45 direction migration-plan.md §7 already noted, where repo-scoped
 * configuration moved out of the host database and into the repo. So resolution is a local config
 * read, the featureflow edge disappears rather than becoming a port, and the daemon module supplies
 * the implementation over the config it is already reading.
 */
public interface ActionResolver {

  /**
   * One declared action, ready to launch.
   *
   * @param id the action's id, as addressed by {@code POST /commands}
   * @param name the display name shown on the Commands list
   * @param executeScript the shell body to run
   * @param interactive whether a human attaches a terminal to it
   * @param environment the action's own environment overlay
   */
  record ResolvedAction(
      String id,
      String name,
      String executeScript,
      boolean interactive,
      Map<String, String> environment) {}

  /** The action with this id, or empty if the checkout declares no such action. */
  Optional<ResolvedAction> resolve(String actionId);

  /** Every declared action, in declaration order. */
  List<ResolvedAction> actions();
}
