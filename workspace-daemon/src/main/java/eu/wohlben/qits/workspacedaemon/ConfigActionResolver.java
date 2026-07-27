package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.commands.ActionResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves actions from the checkout's own {@code .qits-config.yml}.
 *
 * <p>This is the whole of what {@code featureflow.ActionResolutionService} did for command
 * launches, and it needed no port. The host resolved an {@code actionId} against its
 * {@code ActionConfiguration} tables; {@link DaemonQitsConfig} already parses an {@code actions:}
 * block out of the working tree with exactly the fields {@code ResolvedAction} carried — id (which
 * defaults to name), name, description, execute, check, interactive, environment. So the edge to a
 * monolith-only, deferred context (migration-plan.md §3.9, §9 item 6) disappears rather than
 * becoming a permanently-unwired port, and {@code POST /commands} works in the extracted repo.
 *
 * <p>The config is read through a supplier, not captured: it lives in the working tree and an agent
 * can edit it mid-session, so a newly declared action is launchable without restarting the daemon.
 * That is the same reason {@link WorkspaceApi} takes its {@code frameworks:} hints as a supplier.
 *
 * <p>{@code check} and {@code description} are parsed but unused here — {@code check} is the
 * host's pre-flight predicate, which nothing in the launch path consumed either.
 */
final class ConfigActionResolver implements ActionResolver {

  private final Supplier<DaemonQitsConfig> config;

  ConfigActionResolver(Supplier<DaemonQitsConfig> config) {
    this.config = config;
  }

  @Override
  public Optional<ResolvedAction> resolve(String actionId) {
    if (actionId == null || actionId.isBlank()) {
      return Optional.empty();
    }
    return actions().stream().filter(action -> actionId.equals(action.id())).findFirst();
  }

  @Override
  public List<ResolvedAction> actions() {
    DaemonQitsConfig current = config.get();
    if (current == null || current.actions() == null) {
      return List.of();
    }
    return current.actions().stream()
        .filter(declared -> declared.execute() != null && !declared.execute().isBlank())
        .map(
            declared ->
                new ResolvedAction(
                    declared.id(),
                    declared.name(),
                    declared.execute(),
                    declared.interactive(),
                    declared.environment() == null ? Map.of() : declared.environment()))
        .toList();
  }
}
