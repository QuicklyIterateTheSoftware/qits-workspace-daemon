package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Per-line severity stamped on captured log lines as they are buffered. Null on unclassified lines
 * — routine output deliberately carries no severity. Lives here rather than beside the classifier
 * so the log buffer does not depend on whoever supplies the classification; the daemon module
 * provides the {@link LogLineClassifier} implementation.
 */
public enum LogSeverity {
  INFO,
  WARNING,
  ERROR
}
