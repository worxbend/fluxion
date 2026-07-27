package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a {@code compiled-binary} step by handing it to binstaller.
 *
 * <p>binstaller is the binary installer; Fluxion should not have a second one. Delegating gains zip
 * and tar.xz extraction, which Fluxion's own installer never supported — the reason the shipped
 * Fedora profile still installs yazi and zig with hand-written {@code curl | unzip | mv | chmod}.
 *
 * <p>Delegation is attempted, not assumed. When the step cannot be expressed faithfully (a detached
 * GPG signature, a non-SHA-256 checksum, an unmappable archive) or binstaller is unavailable
 * (offline, air-gapped, an unsupported platform), the caller falls back to the built-in installer
 * rather than failing or silently doing less than the profile asked for.
 */
final class DelegatingBinaryInstaller {

  private static final Logger log = LoggerFactory.getLogger(DelegatingBinaryInstaller.class);
  private static final Duration TIMEOUT = Duration.ofMinutes(30);

  private final ShellRunner shellRunner;
  private final ToolResolver toolResolver;

  DelegatingBinaryInstaller(ShellRunner shellRunner, ToolResolver toolResolver) {
    this.shellRunner = shellRunner;
    this.toolResolver = toolResolver;
  }

  /**
   * An instance that never delegates.
   *
   * <p>Used where the built-in path is the subject under test, so the outcome cannot depend on
   * whether the host happens to have binstaller installed.
   */
  static DelegatingBinaryInstaller disabled() {
    return new DelegatingBinaryInstaller(
        (command, env, timeout) -> {
          throw new IllegalStateException("delegation is disabled");
        },
        spec -> {
          throw new ToolResolutionException("delegation is disabled");
        });
  }

  /**
   * Attempts the install through binstaller.
   *
   * @return the outcome, or empty when the caller should use the built-in installer instead
   */
  Optional<StepResult> install(CompiledBinaryModule module) {
    Object translation = BinaryProfileTranslator.translate(module);
    if (translation instanceof BinaryProfileTranslator.Refusal refusal) {
      log.debug("Not delegating {} to binstaller: {}", module.name().value(), refusal.reason());
      return Optional.empty();
    }
    Path profile = null;
    Instant start = Instant.now();
    try {
      profile = writeProfile(module, (String) translation);
      List<String> command =
          List.of(
              toolResolver.resolve(KnownTools.BINSTALLER).toString(),
              "apply",
              "--config",
              profile.toString(),
              "--only",
              module.name().value());
      ProcessResult result = shellRunner.run(command, Map.of(), TIMEOUT);
      Duration elapsed = Duration.between(start, Instant.now());
      return Optional.of(
          result.isSuccess()
              ? new StepResult.Success(module.binaryName(), elapsed)
              : new StepResult.Failure(
                  module.binaryName(), StepOutcome.detail(result), result.exitCode(), elapsed));
    } catch (ToolResolutionException e) {
      // No binstaller on this host and none obtainable: fall back rather than fail, so an offline
      // or air-gapped machine still installs what Fluxion can install itself.
      log.debug("binstaller unavailable, using the built-in installer: {}", e.getMessage());
      return Optional.empty();
    } catch (IOException e) {
      log.debug("Could not stage a binstaller profile: {}", e.getMessage());
      return Optional.empty();
    } finally {
      deleteQuietly(profile);
    }
  }

  /** Renders what binstaller would be asked to do, for {@code plan --show-commands}. */
  Optional<List<String>> commandPreview(CompiledBinaryModule module) {
    if (BinaryProfileTranslator.translate(module) instanceof BinaryProfileTranslator.Refusal) {
      return Optional.empty();
    }
    // The URL and destination stay in the preview: "binstaller apply" alone would hide what is
    // actually being installed, which is the whole point of reading a plan before running it.
    return Optional.of(
        List.of(
            "binstaller",
            "apply",
            "--only",
            module.name().value(),
            "#",
            module.url().toString(),
            "->",
            module.installPath().toString()));
  }

  private Path writeProfile(CompiledBinaryModule module, String yaml) throws IOException {
    Path profile =
        Files.createTempFile("fluxion-binstaller-" + module.name().value() + "-", ".yaml");
    Files.writeString(profile, yaml);
    return profile;
  }

  private void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // A leftover temp profile is not worth failing an install over.
    }
  }
}
