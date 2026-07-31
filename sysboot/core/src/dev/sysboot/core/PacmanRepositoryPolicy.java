package dev.sysboot.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class PacmanRepositoryPolicy {

  private static final Set<String> SIG_LEVEL_TOKENS =
      Set.of(
          "Never",
          "Optional",
          "Required",
          "TrustedOnly",
          "TrustAll",
          "PackageNever",
          "PackageOptional",
          "PackageRequired",
          "PackageTrustedOnly",
          "PackageTrustAll",
          "DatabaseNever",
          "DatabaseOptional",
          "DatabaseRequired",
          "DatabaseTrustedOnly",
          "DatabaseTrustAll");

  private PacmanRepositoryPolicy() {}

  public static void validate(
      Path configPath, Optional<String> sigLevel, Optional<Path> include, boolean enabled) {
    RepositoryDestinationPolicy.requirePacmanConfig(configPath);
    include.ifPresent(RepositoryDestinationPolicy::requirePacmanInclude);
    sigLevel.ifPresent(PacmanRepositoryPolicy::requireSafeSigLevel);
    requireSignatureTrust(sigLevel, enabled);
  }

  public static void requireSignatureTrust(Optional<String> sigLevel, boolean enabled) {
    sigLevel.ifPresent(PacmanRepositoryPolicy::requireSafeSigLevel);
    if (enabled) {
      requireEffectiveTrust(sigLevel);
    }
  }

  private static void requireSafeSigLevel(String sigLevel) {
    String value = sigLevel.strip();
    if (value.isEmpty()
        || containsControl(value)
        || !SIG_LEVEL_TOKENS.containsAll(List.of(value.split(" +")))) {
      throw new IllegalArgumentException(
          "Pacman SigLevel must contain only supported single-line tokens");
    }
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static void requireEffectiveTrust(Optional<String> sigLevel) {
    if (sigLevel.isEmpty()) {
      throw new IllegalArgumentException(
          "Enabled Pacman repositories must declare a signed and trusted SigLevel");
    }
    var packages = new SignaturePolicy();
    var databases = new SignaturePolicy();
    for (String token : sigLevel.orElseThrow().split(" +")) {
      apply(token, packages, databases);
    }
    if (!packages.secure() || !databases.secure()) {
      throw new IllegalArgumentException(
          "Enabled Pacman repositories must require signed, trusted packages and databases");
    }
  }

  private static void apply(String token, SignaturePolicy packages, SignaturePolicy databases) {
    if (token.startsWith("Package")) {
      applySetting(token.substring("Package".length()), packages);
    } else if (token.startsWith("Database")) {
      applySetting(token.substring("Database".length()), databases);
    } else {
      applySetting(token, packages);
      applySetting(token, databases);
    }
  }

  private static void applySetting(String setting, SignaturePolicy policy) {
    switch (setting) {
      case "Never", "Optional" -> policy.required = false;
      case "Required" -> policy.required = true;
      case "TrustAll" -> policy.trustedOnly = false;
      case "TrustedOnly" -> policy.trustedOnly = true;
      default -> throw new IllegalArgumentException("Unsupported Pacman SigLevel token");
    }
  }

  private static final class SignaturePolicy {
    private boolean required;
    private boolean trustedOnly;

    private boolean secure() {
      return required && trustedOnly;
    }
  }
}
