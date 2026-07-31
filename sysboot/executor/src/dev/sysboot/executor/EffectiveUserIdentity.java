package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

final class EffectiveUserIdentity {

  private static final Path PROCESS_STATUS = Path.of("/proc/self/status");

  private EffectiveUserIdentity() {}

  static OptionalLong current() {
    return current(Files::readString);
  }

  static OptionalLong current(StatusReader statusReader) {
    try {
      return parse(statusReader.read(PROCESS_STATUS));
    } catch (IOException | SecurityException e) {
      return OptionalLong.empty();
    }
  }

  static OptionalLong parse(String status) {
    return status
        .lines()
        .filter(line -> line.startsWith("Uid:"))
        .findFirst()
        .map(EffectiveUserIdentity::parseEffectiveUid)
        .orElseGet(OptionalLong::empty);
  }

  private static OptionalLong parseEffectiveUid(String line) {
    String[] values = line.substring("Uid:".length()).trim().split("\\s+");
    if (values.length < 2) {
      return OptionalLong.empty();
    }
    try {
      long effectiveUid = Long.parseLong(values[1]);
      return effectiveUid >= 0 ? OptionalLong.of(effectiveUid) : OptionalLong.empty();
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  @FunctionalInterface
  interface StatusReader {

    String read(Path path) throws IOException;
  }
}
