package dev.sysboot.executor;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.PrivilegeGate;
import dev.sysboot.core.PrivilegePreflight;

public final class OncePrivilegeGate implements PrivilegeGate {

  private final PrivilegePreflight preflight;
  private final Runnable rootExecutionGuard;
  private boolean verified;

  public OncePrivilegeGate(PrivilegePreflight preflight) {
    this(preflight, RootExecutionGuard::verify);
  }

  OncePrivilegeGate(PrivilegePreflight preflight, Runnable rootExecutionGuard) {
    this.preflight = preflight;
    this.rootExecutionGuard = rootExecutionGuard;
  }

  @Override
  public synchronized void verify(BootstrapConfig config) {
    rootExecutionGuard.run();
    if (!config.policy().requireSudoDefault().orElse(false) || verified) {
      return;
    }
    preflight.verify();
    verified = true;
  }
}
