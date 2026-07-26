package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Nerd Font installation, delegated to {@code nerd-fonts-installer}.
 *
 * @param configPath an existing installer config to use as-is; when absent, Fluxion generates one
 *     from {@code config}. Pointing at the user's own {@code
 *     ~/.config/nerd-fonts-installer/config.yaml} keeps one source of truth for the font set.
 */
public record NerdFontModule(
    ModuleName name,
    String installerVersion,
    String nerdfontBinary,
    NerdFontConfig config,
    Optional<Path> configPath,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public NerdFontModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(installerVersion);
    Objects.requireNonNull(nerdfontBinary);
    Objects.requireNonNull(config);
    Objects.requireNonNull(configPath);
    Objects.requireNonNull(probeCommand);
    if (installerVersion.isBlank()) {
      throw new IllegalArgumentException("installerVersion must not be blank");
    }
    if (nerdfontBinary.isBlank()) {
      throw new IllegalArgumentException("nerdfontBinary must not be blank");
    }
  }

  public NerdFontModule(
      ModuleName name,
      String installerVersion,
      String nerdfontBinary,
      NerdFontConfig config,
      Optional<String> probeCommand) {
    this(name, installerVersion, nerdfontBinary, config, Optional.empty(), probeCommand);
  }
}
