package dev.sysboot.executor;

import java.nio.file.FileSystems;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Resolves the non-root account targeted by user-level host changes. */
final class TargetUserResolver {

  private static final Pattern SAFE_USERNAME = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");

  private TargetUserResolver() {}

  static String resolve(Optional<String> configured) {
    return requireSafe(configured.orElseGet(() -> invokingUser(hostContext())));
  }

  static String resolve() {
    return resolve(Optional.empty());
  }

  static String invokingUser(HostContext host) {
    if (host.effectiveUid() == 0L
        && host.sudoUser()
            .filter(TargetUserResolver::isSafe)
            .filter(host.accountExists())
            .isPresent()) {
      return host.sudoUser().orElseThrow();
    }
    return requireSafe(host.currentUser());
  }

  private static HostContext hostContext() {
    return new HostContext(
        EffectiveUserIdentity.current().orElse(-1L),
        Optional.ofNullable(System.getenv("SUDO_USER")),
        System.getProperty("user.name", ""),
        TargetUserResolver::accountExists);
  }

  private static boolean accountExists(String username) {
    try {
      FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(username);
      return true;
    } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
      return false;
    }
  }

  private static String requireSafe(String username) {
    if (!isSafe(username)) {
      throw new IllegalArgumentException("target username is not a safe Linux account name");
    }
    return username;
  }

  private static boolean isSafe(String username) {
    return username != null && SAFE_USERNAME.matcher(username).matches();
  }

  record HostContext(
      long effectiveUid,
      Optional<String> sudoUser,
      String currentUser,
      Predicate<String> accountExists) {}
}
