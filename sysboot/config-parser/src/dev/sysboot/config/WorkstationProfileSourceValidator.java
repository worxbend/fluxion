package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.SourceDocument;
import dev.sysboot.config.yaml.contract.SourceSpecDocument;
import dev.sysboot.config.yaml.contract.SourcesDocument;
import dev.sysboot.config.yaml.contract.WorkstationChecksumDocument;
import dev.sysboot.core.PacmanRepositoryPolicy;
import dev.sysboot.core.SourceUrlPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

final class WorkstationProfileSourceValidator {

  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

  void validate(SourcesDocument sources, List<String> errors) {
    if (sources == null) {
      return;
    }
    validateChecksums("spec.sources.entries", sources.entries(), errors);
    validateSources("spec.sources.apt", sources.apt(), this::validateAptSource, errors);
    validateSources("spec.sources.dnf", sources.dnf(), this::validateRpmSource, errors);
    validateSources("spec.sources.rpm", sources.rpm(), this::validateRpmSource, errors);
    validateSources("spec.sources.pacman", sources.pacman(), this::validatePacmanSource, errors);
    validateSources("spec.sources.zypper", sources.zypper(), this::validateRpmSource, errors);
    validateSources("spec.sources.flatpak", sources.flatpak(), this::validateFlatpakSource, errors);
  }

  private void validateSources(
      String path,
      List<SourceDocument> sources,
      BiConsumer<SourceContext, List<String>> specValidator,
      List<String> errors) {
    for (int index = 0; index < sources.size(); index++) {
      validateSource(path + "[" + index + "]", sources.get(index), specValidator, errors);
    }
  }

  private void validateSource(
      String path,
      SourceDocument source,
      BiConsumer<SourceContext, List<String>> specValidator,
      List<String> errors) {
    validateRequiredText(path + ".name", source.name().orElse(null), errors);
    SourceSpecDocument spec = source.spec().orElse(null);
    if (spec == null) {
      errors.add(path + ".spec is required");
      return;
    }
    specValidator.accept(new SourceContext(path + ".spec", spec), errors);
  }

  private void validateAptSource(SourceContext context, List<String> errors) {
    SourceSpecDocument spec = context.spec();
    validateRequiredText(context.path() + ".source", spec.source().orElse(null), errors);
    validateAptSourceEntry(context.path() + ".source", spec.source().orElse(null), errors);
    validateAbsolutePath(context.path() + ".sourceList", spec.sourceList().orElse(null), errors);
    validateHttpUrl(context.path() + ".signingKeyUrl", spec.signingKeyUrl().orElse(null), errors);
    validateAbsolutePath(context.path() + ".keyring", spec.keyring().orElse(null), errors);
    validateChecksum(context.path() + ".checksum", spec.checksum().orElse(null), errors);
    validateArtifactChecksum(
        context.path(), spec.signingKeyUrl().orElse(null), spec.checksum().orElse(null), errors);
  }

  private void validateRpmSource(SourceContext context, List<String> errors) {
    SourceSpecDocument spec = context.spec();
    validateRequiredText(context.path() + ".id", spec.id().orElse(null), errors);
    validateRequiredHttpUrl(context.path() + ".baseUrl", spec.baseUrl().orElse(null), errors);
    validateAbsolutePath(context.path() + ".repoFile", spec.repoFile().orElse(null), errors);
    validateHttpUrl(context.path() + ".gpgKeyUrl", spec.gpgKeyUrl().orElse(null), errors);
    validateRequiredGpgKey(context.path(), spec, errors);
    validateEnabledGpgCheck(context.path(), spec, errors);
    validateChecksum(context.path() + ".checksum", spec.checksum().orElse(null), errors);
    validateArtifactChecksum(
        context.path(), spec.gpgKeyUrl().orElse(null), spec.checksum().orElse(null), errors);
  }

  private void validateFlatpakSource(SourceContext context, List<String> errors) {
    SourceSpecDocument spec = context.spec();
    validateRequiredText(context.path() + ".remote", spec.remote().orElse(null), errors);
    validateRequiredHttpUrl(context.path() + ".url", spec.url().orElse(null), errors);
    validateChecksum(context.path() + ".checksum", spec.checksum().orElse(null), errors);
    if (spec.checksum().isEmpty()) {
      errors.add(context.path() + ".checksum is required for the Flatpak repository descriptor");
    }
  }

  private void validatePacmanSource(SourceContext context, List<String> errors) {
    SourceSpecDocument spec = context.spec();
    validateRequiredHttpUrl(context.path() + ".server", spec.server().orElse(null), errors);
    validateAbsolutePath(context.path() + ".config", spec.config().orElse(null), errors);
    validateAbsolutePath(context.path() + ".include", spec.include().orElse(null), errors);
    validatePacmanTrust(context.path(), spec, errors);
    validateChecksum(context.path() + ".checksum", spec.checksum().orElse(null), errors);
    if (spec.checksum().isPresent()) {
      errors.add(context.path() + ".checksum has no finite Pacman source artifact to verify");
    }
  }

  private void validateRequiredGpgKey(String path, SourceSpecDocument spec, List<String> errors) {
    if (spec.gpgCheck().orElse(true) && isBlank(spec.gpgKeyUrl().orElse(null))) {
      errors.add(path + ".gpgKeyUrl is required when gpgCheck is true");
    }
  }

  private void validateEnabledGpgCheck(String path, SourceSpecDocument spec, List<String> errors) {
    if (spec.enabled().orElse(true) && !spec.gpgCheck().orElse(true)) {
      errors.add(path + ".gpgCheck must be true for an enabled repository");
    }
  }

  private void validatePacmanTrust(String path, SourceSpecDocument spec, List<String> errors) {
    try {
      PacmanRepositoryPolicy.requireSignatureTrust(spec.sigLevel(), spec.enabled().orElse(true));
    } catch (IllegalArgumentException e) {
      errors.add(path + ".sigLevel " + e.getMessage());
    }
  }

  private void validateChecksums(String path, List<SourceDocument> sources, List<String> errors) {
    for (int index = 0; index < sources.size(); index++) {
      SourceSpecDocument spec = sources.get(index).spec().orElse(null);
      if (spec != null) {
        validateChecksum(
            path + "[" + index + "].spec.checksum", spec.checksum().orElse(null), errors);
      }
    }
  }

  private void validateChecksum(
      String path, WorkstationChecksumDocument checksum, List<String> errors) {
    if (checksum == null) {
      return;
    }
    validateChecksumAlgorithm(path, checksum.algorithm().orElse(null), errors);
    validateChecksumValue(path, checksum.value().orElse(null), errors);
  }

  private void validateChecksumAlgorithm(String path, String algorithm, List<String> errors) {
    if (isBlank(algorithm)) {
      errors.add(path + ".algorithm is required");
      return;
    }
    String normalized = algorithm.strip().replace("-", "").toLowerCase(Locale.ROOT);
    if (!"sha256".equals(normalized)) {
      errors.add(path + ".algorithm unsupported checksum algorithm '" + algorithm + "'");
    }
  }

  private void validateChecksumValue(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + ".value is required");
    } else if (!SHA_256_HEX.matcher(value.strip()).matches()) {
      errors.add(path + ".value must be a 64-character hexadecimal SHA-256 digest");
    }
  }

  private void validateRequiredText(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " is required");
    }
  }

  private void validateRequiredHttpUrl(String path, String value, List<String> errors) {
    validateRequiredText(path, value, errors);
    if (!isBlank(value)) {
      validateHttpUrl(path, value, errors);
    }
  }

  private void validateHttpUrl(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      return;
    }
    try {
      URI uri = new URI(value.strip());
      if (!uri.isAbsolute()
          || !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        errors.add(path + " must be an absolute HTTPS URL without user-info");
      }
    } catch (URISyntaxException e) {
      errors.add(path + " must be a valid HTTPS URL without user-info");
    }
  }

  private void validateAptSourceEntry(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      return;
    }
    try {
      SourceUrlPolicy.aptRepositoryUri(value);
    } catch (IllegalArgumentException e) {
      errors.add(path + " must contain an HTTPS repository URL without user-info");
    }
  }

  private void validateArtifactChecksum(
      String path, String artifactUrl, WorkstationChecksumDocument checksum, List<String> errors) {
    if (!isBlank(artifactUrl) && checksum == null) {
      errors.add(path + ".checksum is required for the remote signing key");
    } else if (isBlank(artifactUrl) && checksum != null) {
      errors.add(path + ".checksum has no remote signing-key artifact to verify");
    }
  }

  private void validateAbsolutePath(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      return;
    }
    try {
      if (!Path.of(value.strip()).isAbsolute()) {
        errors.add(path + " must be an absolute path");
      }
    } catch (InvalidPathException e) {
      errors.add(path + " must be a valid path");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record SourceContext(String path, SourceSpecDocument spec) {}
}
