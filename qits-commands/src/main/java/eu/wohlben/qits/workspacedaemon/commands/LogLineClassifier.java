package eu.wohlben.qits.workspacedaemon.commands;

import java.util.Optional;

/**
 * Classifies one raw captured log line into a {@link LogSeverity}, or empty for routine output.
 * Declared here so the log buffer can stamp severities without depending on whoever knows the log
 * vocabulary; the daemon module supplies the implementation. Must be cheap and local — it runs per
 * line on the capture path.
 */
@FunctionalInterface
public interface LogLineClassifier {

  Optional<LogSeverity> classify(String rawLine);
}
