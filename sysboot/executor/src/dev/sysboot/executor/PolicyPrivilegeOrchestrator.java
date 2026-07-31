package dev.sysboot.executor;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PrivilegeGate;
import java.util.List;

public final class PolicyPrivilegeOrchestrator implements BootstrapOrchestrator {

  private final BootstrapOrchestrator delegate;
  private final PrivilegeGate privilegeGate;

  public PolicyPrivilegeOrchestrator(BootstrapOrchestrator delegate, PrivilegeGate privilegeGate) {
    this.delegate = delegate;
    this.privilegeGate = privilegeGate;
  }

  @Override
  public void execute(BootstrapConfig config, ExecutionEventListener listener) {
    preflight(config);
    delegate.execute(config, listener);
  }

  @Override
  public void execute(
      BootstrapConfig config, ExecutionEventListener listener, CancellationSignal cancellation) {
    preflight(config);
    delegate.execute(config, listener, cancellation);
  }

  @Override
  public void execute(
      BootstrapConfig config,
      List<Phase> executionPhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    preflight(config);
    delegate.execute(config, executionPhases, listener, cancellation);
  }

  @Override
  public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
    delegate.dryRun(config, listener);
  }

  private void preflight(BootstrapConfig config) {
    privilegeGate.verify(config);
  }
}
