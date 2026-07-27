package eu.wohlben.qits.workspacedaemon.commands;

/**
 * No command with that id — a 404 at the API boundary.
 *
 * <p>Its own type rather than the monolith's shared {@code domain.error.NotFoundException}:
 * migration-plan.md §5 has every target take a copy of the error types it actually throws, and this
 * module throws exactly two. Carrying a five-class error package to use two of it would be worse
 * than naming them.
 *
 * <p>Note this is now also the answer for a command that <em>did</em> exist in a previous container,
 * or that has been evicted from {@link CommandStore}'s bound. The host could distinguish "never
 * existed" from "long finished" because the row was durable; here both are simply absent.
 */
public class CommandNotFoundException extends RuntimeException {

  public CommandNotFoundException(String message) {
    super(message);
  }
}
