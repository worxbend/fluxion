package dev.sysboot.executor;

import java.util.OptionalLong;

final class RootExecutionGuard {

  private RootExecutionGuard() {}

  static void verify() {
    verify(EffectiveUserIdentity.current());
  }

  static void verify(OptionalLong effectiveUid) {
    if (effectiveUid.isEmpty()) {
      throw new ShellExecutionException("Cannot determine effective UID; refusing live apply");
    }
    if (effectiveUid.getAsLong() == 0L) {
      throw new ShellExecutionException(
          "Refusing live apply as root: Fluxion has no safe user-drop execution path");
    }
  }
}
