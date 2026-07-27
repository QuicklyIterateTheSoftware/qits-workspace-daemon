package eu.wohlben.qits.workspacedaemon.commands;

/** A malformed request — an unknown action, or a session id that is not one. A 400. */
public class InvalidCommandRequestException extends RuntimeException {

  public InvalidCommandRequestException(String message) {
    super(message);
  }
}
