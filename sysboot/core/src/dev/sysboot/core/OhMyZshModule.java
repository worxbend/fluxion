package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record OhMyZshModule(
    ModuleName name,
    Path installDir,
    String installerRevision,
    Sha256Digest installerSha256,
    Optional<String> probeCommand)
    implements BootstrapModule {

  private static final Pattern COMMIT_REVISION = Pattern.compile("[0-9a-fA-F]{40}");

  public OhMyZshModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(installDir);
    Objects.requireNonNull(installerRevision);
    Objects.requireNonNull(installerSha256);
    Objects.requireNonNull(probeCommand);
    installerRevision = installerRevision.strip().toLowerCase(Locale.ROOT);
    if (!COMMIT_REVISION.matcher(installerRevision).matches()) {
      throw new IllegalArgumentException(
          "Oh My Zsh installerRevision must be a full 40-character commit");
    }
  }
}
