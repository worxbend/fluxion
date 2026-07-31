package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.PublicUrl;
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
 * and tar.xz extraction, which Fluxion's own installer never supported and profiles historically
 * handled with hand-written download and extraction commands.
 *
 * <p>Delegation is attempted, not assumed. When the step cannot be expressed faithfully (a detached
 * GPG signature, a non-SHA-256 checksum, an unmappable archive) or binstaller is unavailable
 * (offline, air-gapped, an unsupported platform), control returns to the caller. The caller may use
 * its built-in path only for formats it can safely install; delegation-only formats fail closed.
 */
final class DelegatingBinaryInstaller {

  private static final Logger log = LoggerFactory.getLogger(DelegatingBinaryInstaller.class);
  private static final Duration TIMEOUT = Duration.ofMinutes(30);

  private final ShellRunner shellRunner;
  private final ToolResolver toolResolver;
  private final boolean enabled;

  DelegatingBinaryInstaller(ShellRunner shellRunner, ToolResolver toolResolver) {
    this(shellRunner, toolResolver, true);
  }

  private DelegatingBinaryInstaller(
      ShellRunner shellRunner, ToolResolver toolResolver, boolean enabled) {
    this.shellRunner = shellRunner;
    this.toolResolver = toolResolver;
    this.enabled = enabled;
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
        },
        false);
  }

  boolean isEnabled() {
    return enabled;
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
      log.debug("binstaller unavailable, returning control to the caller: {}", e.getMessage());
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
            PublicUrl.from(module.url().value()),
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
