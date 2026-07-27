package eu.wohlben.qits.workspacedaemon.commands;

/**
 * Which stream a captured log line came from. A PTY merges a process's stdout and stderr into one
 * stream, so {@link #OUTPUT} covers both; {@link #STDIN} is what the human typed. {@link #STDERR}
 * is reserved for a possible future pipe-based (non-PTY) capture mode and is unused today. {@link
 * #TRANSCRIPT} lines are not intercepted stdio at all: they are the agent-session transcript (main
 * session JSONL plus subagent sidechains) imported from the harness's own persistence after the
 * command exits.
 */
public enum LogChannel {
  STDIN,
  OUTPUT,
  STDERR,
  TRANSCRIPT
}
