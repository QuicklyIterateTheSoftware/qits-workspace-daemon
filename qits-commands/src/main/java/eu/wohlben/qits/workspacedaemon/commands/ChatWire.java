package eu.wohlben.qits.workspacedaemon.commands;

/**
 * The one seam a {@link ChatProtocol} uses to feed the unified conversation stream back into its
 * {@link ChatSession}: each call delivers one line of newline-delimited JSON in the event envelope
 * the frontend renders (an assistant/user/tool event, a synthetic user echo, a result). The session
 * rings it for re-attach, broadcasts it live, and records the ones that must survive replay — the
 * protocol stays ignorant of all of that.
 */
@FunctionalInterface
public interface ChatWire {

  /** Ring + broadcast one envelope line of the conversation. */
  void emit(String envelopeLine);
}
