package dev.sysboot.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Git configuration entries, applied with {@code git config}.
 *
 * <p>Expressed as typed entries rather than a shell command so each key can be probed individually:
 * {@code fluxion diff} can then report {@code user.name: alice -> w0rxbend} instead of re-running
 * an opaque command every time.
 */
public record GitConfigModule(
    ModuleName name, GitConfigScope scope, Map<String, String> entries, boolean continueOnError)
    implements BootstrapModule {

  public GitConfigModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(scope);
    entries = Map.copyOf(Objects.requireNonNull(entries));
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("git-config requires at least one entry");
    }
    entries.keySet().forEach(GitConfigModule::requireValidKey);
  }

  private static void requireValidKey(String key) {
    if (key == null || key.isBlank() || !key.contains(".")) {
      throw new IllegalArgumentException(
          "git-config keys must be section.key, for example user.email, but got: " + key);
    }
  }

  /** Keys in a stable order, so plan output and fingerprints do not depend on map iteration. */
  public List<String> sortedKeys() {
    return entries.keySet().stream().sorted().toList();
  }

  public String itemKey(String key) {
    return scope.flag().substring(2) + ":" + key;
  }
}
