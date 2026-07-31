package dev.sysboot.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository signing keys imported before the repositories that need them.
 *
 * <p>A key is the prerequisite for every third-party repository, and importing one is the single
 * most security-sensitive thing a bootstrap does: it decides what the machine will trust to install
 * software as root. {@code fingerprint} is therefore mandatory and checked before trust is changed.
 */
public record GpgKeyModule(ModuleName name, List<GpgKey> keys, boolean continueOnError)
    implements BootstrapModule {

  public GpgKeyModule {
    Objects.requireNonNull(name);
    keys = List.copyOf(Objects.requireNonNull(keys));
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("gpg-key requires at least one key");
    }
    if (keys.stream().map(GpgKey::itemKey).distinct().count() != keys.size()) {
      throw new IllegalArgumentException("gpg-key contains duplicate canonical key identities");
    }
  }

  /**
   * @param keyring where a dearmoured key is written, for apt-style {@code signed-by} sources.
   *     Empty means import into the RPM database instead.
   * @param fingerprint expected full fingerprint, verified before import
   */
  public record GpgKey(String url, Optional<java.nio.file.Path> keyring, String fingerprint) {

    public GpgKey {
      Objects.requireNonNull(url);
      Objects.requireNonNull(keyring);
      Objects.requireNonNull(fingerprint);
      if (url.isBlank()) {
        throw new IllegalArgumentException("gpg-key url must not be blank");
      }
      requireTrustedUrl(url);
      fingerprint = normalizeFingerprint(fingerprint);
      keyring = keyring.map(RepositoryDestinationPolicy::requireGpgKeyring);
    }

    private static void requireTrustedUrl(String value) {
      try {
        URI uri = new URI(value);
        boolean https =
            "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null;
        boolean file =
            "file".equalsIgnoreCase(uri.getScheme())
                && uri.getUserInfo() == null
                && java.nio.file.Path.of(uri).isAbsolute();
        if (!https && !file) {
          throw new IllegalArgumentException("untrusted URL");
        }
      } catch (URISyntaxException | IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "gpg-key url must be HTTPS without user-info or an absolute file URI", e);
      }
    }

    private static String normalizeFingerprint(String value) {
      String normalized = value.replace(" ", "").toUpperCase(Locale.ROOT);
      if (!normalized.matches("[0-9A-Fa-f]{40}")) {
        throw new IllegalArgumentException(
            "gpg-key fingerprint must be 40 hex characters, but got: " + value);
      }
      return normalized;
    }

    public String itemKey() {
      return keyring.map(java.nio.file.Path::toString).orElse("fingerprint:" + fingerprint);
    }

    public String displayName() {
      return keyring.map(java.nio.file.Path::toString).orElseGet(this::publicUrl);
    }

    /** Returns the configured URL without signed-request query parameters or fragments. */
    public String publicUrl() {
      return PublicUrl.from(url);
    }
  }
}
