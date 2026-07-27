package dev.sysboot.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository signing keys imported before the repositories that need them.
 *
 * <p>A key is the prerequisite for every third-party repository, and importing one is the single
 * most security-sensitive thing a bootstrap does: it decides what the machine will trust to install
 * software as root. {@code fingerprint} is therefore checked after import when supplied, and {@code
 * fluxion lint} warns when it is not.
 */
public record GpgKeyModule(ModuleName name, List<GpgKey> keys, boolean continueOnError)
    implements BootstrapModule {

  public GpgKeyModule {
    Objects.requireNonNull(name);
    keys = List.copyOf(Objects.requireNonNull(keys));
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("gpg-key requires at least one key");
    }
  }

  /**
   * @param keyring where a dearmoured key is written, for apt-style {@code signed-by} sources.
   *     Empty means import into the RPM database instead.
   * @param fingerprint expected full fingerprint, verified after import
   */
  public record GpgKey(
      String url, Optional<java.nio.file.Path> keyring, Optional<String> fingerprint) {

    public GpgKey {
      Objects.requireNonNull(url);
      Objects.requireNonNull(keyring);
      Objects.requireNonNull(fingerprint);
      if (url.isBlank()) {
        throw new IllegalArgumentException("gpg-key url must not be blank");
      }
      if (!url.startsWith("https://") && !url.startsWith("file://")) {
        throw new IllegalArgumentException(
            "gpg-key url must be https or file, since it decides what the machine trusts: " + url);
      }
      fingerprint.ifPresent(GpgKey::requireHexFingerprint);
    }

    private static void requireHexFingerprint(String value) {
      String normalized = value.replace(" ", "");
      if (!normalized.matches("[0-9A-Fa-f]{40}")) {
        throw new IllegalArgumentException(
            "gpg-key fingerprint must be 40 hex characters, but got: " + value);
      }
    }

    public String itemKey() {
      return keyring.map(java.nio.file.Path::toString).orElse(url);
    }
  }
}
