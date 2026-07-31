package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.core.ReleaseTagPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class WorkstationToolingTrustValidator {

  private static final Pattern OPENPGP_FINGERPRINT = Pattern.compile("[0-9a-fA-F]{40}");
  private final WorkstationValidationSupport support;

  WorkstationToolingTrustValidator(WorkstationValidationSupport support) {
    this.support = support;
  }

  void validateNerdFontSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    validateNerdFontConfigShape(path, spec, errors);
    validatePinnedInstaller(
        path + ".spec.installerVersion",
        spec.installerVersion().orElse(dev.sysboot.core.KnownTools.NERD_FONTS_INSTALLER.version()),
        errors);
    support.requirePresent(
        path + ".spec.nerdfontBinary",
        spec.nerdfontBinary()
            .orElse(dev.sysboot.core.KnownTools.NERD_FONTS_INSTALLER.executableName()),
        entryName,
        errors);
    support.validateNonEmptyItems(nerdFontFamiliesPath(path, spec), nerdFontFamilies(spec), errors);
  }

  void validateDotfilesSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.configIsObject()) {
      errors.add(path + ".spec.config for plan entry '" + entryName + "' must be a path string");
    }
    support.requirePresent(
        path + ".spec.config", spec.dotfilesConfig().orElse(null), entryName, errors);
    validatePinnedInstaller(
        path + ".spec.installerVersion",
        spec.installerVersion().orElse(dev.sysboot.core.KnownTools.DOTBOT_GO.version()),
        errors);
    support.requirePresent(
        path + ".spec.dotbotBinary", spec.dotbotBinary().orElse("dotbot"), entryName, errors);
  }

  void validateBinstallerSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.configIsObject()) {
      errors.add(
          path
              + ".spec.config for plan entry '"
              + entryName
              + "' must be a path to a BinaryDistributionProfile, not an inline object");
    }
    support.requirePresent(
        path + ".spec.config", spec.dotfilesConfig().orElse(null), entryName, errors);
    validatePinnedInstaller(
        path + ".spec.installerVersion",
        spec.installerVersion().orElse(dev.sysboot.core.KnownTools.BINSTALLER.version()),
        errors);
    if (spec.locked() && spec.lockFile().isEmpty()) {
      errors.add(
          path
              + ".spec.lockFile is required for plan entry '"
              + entryName
              + "' because locked is true");
    }
  }

  void validateGpgKeySpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.keys().isEmpty()) {
      errors.add(path + ".spec.keys is required for plan entry '" + entryName + "'");
    }
    spec.keys()
        .forEach(
            key -> {
              support.requirePresent(path + ".spec.keys[].url", key.url, entryName, errors);
              support.requirePresent(
                  path + ".spec.keys[].fingerprint", key.fingerprint, entryName, errors);
              if (!support.isBlank(key.url)) {
                validateGpgKeyUrl(path + ".spec.keys[].url", key.url, entryName, errors);
              }
              if (!support.isBlank(key.fingerprint)
                  && !OPENPGP_FINGERPRINT.matcher(key.fingerprint.replace(" ", "")).matches()) {
                errors.add(
                    path
                        + ".spec.keys[].fingerprint for plan entry '"
                        + entryName
                        + "' must be a full 40-character hexadecimal OpenPGP fingerprint");
              }
              if (key.keyring != null) {
                support.validateAbsolutePath(path + ".spec.keys[].keyring", key.keyring, errors);
              }
            });
  }

  void validateToolPackagesSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    support.requirePresent(path + ".spec.backend", spec.backend().orElse(null), entryName, errors);
    spec.backend()
        .filter(backend -> dev.sysboot.core.ToolPackageBackend.fromId(backend).isEmpty())
        .ifPresent(
            backend ->
                errors.add(
                    path
                        + ".spec.backend for plan entry '"
                        + entryName
                        + "' is not a supported backend: "
                        + backend));
    if (spec.packages().isEmpty()) {
      errors.add(path + ".spec.packages is required for plan entry '" + entryName + "'");
    }
  }

  void validateZypperRepositorySpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    support.requirePresent(path + ".spec.baseUrl", spec.baseUrl().orElse(null), entryName, errors);
    spec.baseUrl().ifPresent(url -> support.validateHttpsUrl(path + ".spec.baseUrl", url, errors));
    spec.gpgKeyUrl()
        .ifPresent(url -> support.validateHttpsUrl(path + ".spec.gpgKeyUrl", url, errors));
    support.validateChecksum(path + ".spec.checksum", spec.checksum().orElse(null), errors);
    if (spec.gpgCheck() && spec.gpgKeyUrl().isEmpty()) {
      errors.add(
          path
              + ".spec.gpgKeyUrl is required for plan entry '"
              + entryName
              + "' because gpgCheck is enabled");
    }
    if (spec.repoEnabled() && !spec.gpgCheck()) {
      errors.add(path + ".spec.gpgCheck must be true for enabled plan entry '" + entryName + "'");
    }
    if (spec.gpgKeyUrl().isPresent() != spec.checksum().isPresent()) {
      errors.add(
          path
              + ".spec.gpgKeyUrl and checksum must be configured together for plan entry '"
              + entryName
              + "'");
    }
  }

  private void validateGpgKeyUrl(String path, String value, String entryName, List<String> errors) {
    try {
      URI uri = new URI(value);
      boolean https =
          "https".equalsIgnoreCase(uri.getScheme())
              && uri.getHost() != null
              && uri.getUserInfo() == null;
      boolean file =
          "file".equalsIgnoreCase(uri.getScheme())
              && uri.getUserInfo() == null
              && Path.of(uri).isAbsolute();
      if (!https && !file) {
        errors.add(
            path
                + " for plan entry '"
                + entryName
                + "' must be HTTPS without user-info or an absolute file URI");
      }
    } catch (URISyntaxException | IllegalArgumentException e) {
      errors.add(path + " for plan entry '" + entryName + "' is not a valid trusted key URL");
    }
  }

  private void validateNerdFontConfigShape(
      String path, PlanSpecDocument spec, List<String> errors) {
    if (spec.configIsText()) {
      errors.add(path + ".spec.config must be an object for nerd-fonts");
    }
    spec.destination()
        .ifPresent(
            value ->
                support.requirePresent(path + ".spec.destination", value, "nerd-fonts", errors));
    spec.release().ifPresent(value -> validatePinnedRelease(path + ".spec.release", value, errors));
    spec.nerdFontConfig()
        .ifPresent(
            config -> {
              validatePinnedRelease(path + ".spec.config.release", config.release, errors);
              if (config.destination != null) {
                support.requirePresent(
                    path + ".spec.config.destination", config.destination, "nerd-fonts", errors);
              }
            });
  }

  private void validatePinnedRelease(String path, String release, List<String> errors) {
    if (!ReleaseTagPolicy.isExact(release)) {
      errors.add(path + " must pin an exact release such as v3.4.0");
    }
  }

  private void validatePinnedInstaller(String path, String release, List<String> errors) {
    if (!ReleaseTagPolicy.isExact(release)) {
      errors.add(path + " must pin an exact release such as v1.2.3");
    }
  }

  private List<String> nerdFontFamilies(PlanSpecDocument spec) {
    return spec.nerdFontConfig().map(config -> config.families).orElseGet(spec::families);
  }

  private String nerdFontFamiliesPath(String path, PlanSpecDocument spec) {
    return spec.nerdFontConfig().isPresent()
        ? path + ".spec.config.families"
        : path + ".spec.families";
  }
}
