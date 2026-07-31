package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ProcessResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompiledBinaryProbe implements InstalledProbe {

  private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+[\\w.\\-]*)");
  private static final Duration VERSION_CMD_TIMEOUT = Duration.ofSeconds(3);
  private static final long MAX_HASH_BYTES = HttpBinaryDownloadClient.MAX_FILE_BYTES;

  private final String versionCommand;
  private final String expectedVersionPrefix;
  private final Duration timeout;

  public CompiledBinaryProbe(
      Optional<String> versionCommand, Optional<String> expectedVersionPrefix) {
    this(versionCommand, expectedVersionPrefix, VERSION_CMD_TIMEOUT);
  }

  CompiledBinaryProbe(
      Optional<String> versionCommand, Optional<String> expectedVersionPrefix, Duration timeout) {
    this.versionCommand = versionCommand.orElse(null);
    this.expectedVersionPrefix = expectedVersionPrefix.orElse(null);
    this.timeout = timeout;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.COMPILED_BINARY;
  }

  @Override
  public InstallationStatus probe(String installPath) {
    Path path = Path.of(installPath);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return new InstallationStatus.NotInstalled(installPath);
    }
    return unknown(installPath, "Compiled binary probe requires its configured trust policy");
  }

  @Override
  public InstallationStatus probe(ModuleItem item) {
    if (!(item.configuredModule().orElse(null) instanceof CompiledBinaryModule module)) {
      return probe(item.key());
    }
    if (!item.key().equals(module.installPath().toString())) {
      return unknown(item.key(), "Compiled binary item does not match its configured install path");
    }
    return probeConfigured(module);
  }

  InstallationStatus probeTrustedInstalled(CompiledBinaryModule module) {
    Optional<String> expectedVersion =
        module.expectedVersion().or(() -> Optional.ofNullable(expectedVersionPrefix));
    return probeTrustedPath(
        module.installPath(), module.versionCommand().orElse(null), expectedVersion);
  }

  private InstallationStatus probeConfigured(CompiledBinaryModule module) {
    Path path = module.installPath();
    InstallationStatus basicStatus = basicFileStatus(path);
    if (basicStatus != null) {
      return basicStatus;
    }
    if (module.archivePath().isPresent()) {
      return unknown(path, "Configured checksum covers an archive, not the installed binary");
    }
    var checksum = module.checksum().filter(value -> value.hasValidSha256Value());
    if (checksum.isEmpty()) {
      return unknown(path, "No configured final-byte SHA-256 is available");
    }
    try {
      String actual =
          BinaryDigest.hex(
              new DefaultBinaryFileSystem(),
              path,
              checksum.orElseThrow().algorithm(),
              MAX_HASH_BYTES);
      if (!actual.equals(checksum.orElseThrow().value())) {
        return new InstallationStatus.NotInstalled(path.toString());
      }
    } catch (IOException | NoSuchAlgorithmException e) {
      return unknown(path, "Unable to verify installed binary: " + e.getMessage());
    }
    return probeTrustedPath(path, module.versionCommand().orElse(null), module.expectedVersion());
  }

  private InstallationStatus probeTrustedPath(
      Path path, String configuredVersionCommand, Optional<String> expectedVersion) {
    InstallationStatus basicStatus = basicFileStatus(path);
    if (basicStatus != null) {
      return basicStatus;
    }
    String detected = tryDetectVersion(path, configuredVersionCommand);
    if (expectedVersion.isPresent()
        && (detected == null || !detected.startsWith(expectedVersion.orElseThrow()))) {
      return new InstallationStatus.NotInstalled(path.toString());
    }
    return new InstallationStatus.InstalledByProbe(path.toString(), detected);
  }

  private InstallationStatus basicFileStatus(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return new InstallationStatus.NotInstalled(path.toString());
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(path)) {
      return unknown(path, "Installed path is not a regular executable file");
    }
    return null;
  }

  private String tryDetectVersion(Path binary, String configuredVersionCommand) {
    String command = configuredVersionCommand == null ? versionCommand : configuredVersionCommand;
    if (command != null) {
      return runVersionCommand(List.of("/bin/sh", "-c", command));
    }
    return runVersionCommand(List.of(binary.toString(), "--version"));
  }

  private String runVersionCommand(List<String> command) {
    try {
      ProcessResult result =
          ProcessExecution.run(ProcessExecution.Request.of(command, Map.of(), timeout));
      if (!result.isSuccess()) {
        return null;
      }
      return result
          .stdout()
          .strip()
          .lines()
          .findFirst()
          .flatMap(
              line -> {
                Matcher m = VERSION_PATTERN.matcher(line);
                return m.find() ? Optional.of(m.group(1)) : Optional.empty();
              })
          .orElse(null);
    } catch (ShellExecutionException e) {
      return null;
    }
  }

  private InstallationStatus.Unknown unknown(Path path, String reason) {
    return unknown(path.toString(), reason);
  }

  private InstallationStatus.Unknown unknown(String item, String reason) {
    return new InstallationStatus.Unknown(item, reason);
  }
}
