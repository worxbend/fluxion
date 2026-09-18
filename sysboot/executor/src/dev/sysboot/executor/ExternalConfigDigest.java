package dev.sysboot.executor;

import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.DotbotModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/**
 * SHA-256 of the file a delegated step points at, for kinds whose item key names a path rather than
 * the work itself.
 *
 * <p>{@code binstaller-profile} and {@code dotbot} hand their work to another tool and carry only a
 * path to that tool's own config. Two runs against the same path record the same item key even when
 * the file behind it changed, so {@link SkipEvaluator} could not tell a stale "already installed"
 * state entry from a current one. Folding this digest into the recorded checksum closes that gap
 * without this layer learning binstaller's or dotbot's schema — the bytes are hashed, never parsed,
 * so a new tool, a bumped version, or an edited URL all move the digest and an upstream field
 * rename cannot break this.
 */
final class ExternalConfigDigest {

  private ExternalConfigDigest() {}

  static Optional<String> of(BootstrapModule module) {
    return configPath(module).flatMap(ExternalConfigDigest::digest);
  }

  private static Optional<Path> configPath(BootstrapModule module) {
    return switch (module) {
      case BinstallerModule binstaller -> Optional.of(binstaller.config());
      case DotbotModule dotbot -> Optional.of(dotbot.config());
      default -> Optional.empty();
    };
  }

  private static Optional<String> digest(Path path) {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      return Optional.of(ArtifactDigests.sha256(path).value());
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
