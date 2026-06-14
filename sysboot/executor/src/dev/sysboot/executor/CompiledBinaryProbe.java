package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompiledBinaryProbe implements InstalledProbe {

  private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+[\\w.\\-]*)");
  private static final int VERSION_CMD_TIMEOUT_SECONDS = 3;

  private final String versionCommand;
  private final String expectedVersionPrefix;

  public CompiledBinaryProbe(
      Optional<String> versionCommand, Optional<String> expectedVersionPrefix) {
    this.versionCommand = versionCommand.orElse(null);
    this.expectedVersionPrefix = expectedVersionPrefix.orElse(null);
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.COMPILED_BINARY;
  }

  @Override
  public InstallationStatus probe(String installPath) {
    Path path = Path.of(installPath);

    if (!Files.exists(path)) {
      return new InstallationStatus.NotInstalled(installPath);
    }
    if (!Files.isExecutable(path)) {
      return new InstallationStatus.Unknown(
          installPath, "File exists but is not executable: " + installPath);
    }

    String detected = tryDetectVersion(path);

    if (expectedVersionPrefix != null && detected != null) {
      if (!detected.startsWith(expectedVersionPrefix)) {
        return new InstallationStatus.NotInstalled(installPath);
      }
    }

    return new InstallationStatus.InstalledByProbe(installPath, detected);
  }

  private String tryDetectVersion(Path binary) {
    if (versionCommand != null) {
      return runVersionCommand(versionCommand);
    }
    return runVersionCommand(binary + " --version");
  }

  private String runVersionCommand(String command) {
    try {
      Process process =
          new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
      InputStream in = process.getInputStream();
      boolean done = process.waitFor(VERSION_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        return null;
      }
      String output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      return output
          .lines()
          .findFirst()
          .flatMap(
              line -> {
                Matcher m = VERSION_PATTERN.matcher(line);
                return m.find() ? Optional.of(m.group(1)) : Optional.empty();
              })
          .orElse(null);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}
