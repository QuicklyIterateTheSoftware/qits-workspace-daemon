package eu.wohlben.qits.workspacedaemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * The two facts this container's cgroup v2 memory controller can tell about an agent that died by
 * SIGKILL: whether the OOM killer did it ({@code memory.events}' {@code oom_kill} counter), and
 * what the cap was ({@code memory.max}).
 *
 * <p>Inside a container {@code /sys/fs/cgroup} is the container's own cgroup (the namespace roots
 * it there), so the counter counts kills in this workspace and nowhere else — which is what makes a
 * rise in it while an agent ran evidence about <em>that</em> agent.
 *
 * <p><b>Every read is tolerant of the file not being there.</b> A cgroup v1 host has neither file,
 * a test has no cgroup at all, and an unreadable file is the same answer as an absent one: empty.
 * The caller then reports a plain SIGKILL rather than guessing an OOM, and never fails over it.
 */
final class CgroupMemory {

  /** The container's own cgroup v2 directory. */
  static final Path CONTAINER = Path.of("/sys/fs/cgroup");

  private final Path dir;

  CgroupMemory(Path dir) {
    this.dir = dir;
  }

  /** How many processes the OOM killer has taken in this cgroup, or empty when unreadable. */
  OptionalLong oomKills() {
    String events = read("memory.events");
    if (events == null) {
      return OptionalLong.empty();
    }
    for (String line : events.split("\n")) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length == 2 && "oom_kill".equals(parts[0])) {
        return parse(parts[1]);
      }
    }
    return OptionalLong.empty();
  }

  /** The memory cap in bytes, or empty when there is none ({@code max}) or it is unreadable. */
  OptionalLong limitBytes() {
    String max = read("memory.max");
    if (max == null || "max".equals(max.trim())) {
      return OptionalLong.empty();
    }
    return parse(max.trim());
  }

  /** A cap the way a person reads it: {@code 4 GiB}, {@code 512 MiB}, or bytes when neither fits. */
  static String describe(long bytes) {
    long gib = 1L << 30;
    long mib = 1L << 20;
    if (bytes >= gib && bytes % gib == 0) {
      return (bytes / gib) + " GiB";
    }
    if (bytes >= gib) {
      return String.format(java.util.Locale.ROOT, "%.1f GiB", bytes / (double) gib);
    }
    if (bytes >= mib && bytes % mib == 0) {
      return (bytes / mib) + " MiB";
    }
    return bytes + " bytes";
  }

  private String read(String file) {
    try {
      return Files.readString(dir.resolve(file));
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static OptionalLong parse(String value) {
    try {
      return OptionalLong.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }
}
