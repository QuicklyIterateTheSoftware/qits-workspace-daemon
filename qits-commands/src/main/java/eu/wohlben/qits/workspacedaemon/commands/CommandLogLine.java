package eu.wohlben.qits.workspacedaemon.commands;

import java.time.Instant;

/**
 * One captured line of a command's log.
 *
 * <p>On the host this was a {@code command_log_line} row with a CLOB body and a sequence-generated
 * id — high volume, and unbounded. Here it is a record in a per-command {@link CommandLogBuffer}
 * with a fixed capacity, which is the one behavioural difference worth knowing about: a command
 * that outputs more than the buffer holds loses its oldest lines rather than growing a table. See
 * that class for the bound and why.
 *
 * @param sequence the monotonic per-command ordinal (stable sort key), assigned at capture
 * @param channel which stream the line came from (STDIN vs merged OUTPUT vs imported TRANSCRIPT)
 * @param content the raw line text (may contain ANSI escapes)
 * @param severity classified severity where a classifier is wired; null on routine output
 * @param timestamp when the line completed
 */
public record CommandLogLine(
    long sequence, LogChannel channel, String content, LogSeverity severity, Instant timestamp) {}
