package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PrivilegePreflight;
import dev.sysboot.core.ProfileName;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyPrivilegeOrchestratorTest {

  private static final ExecutionEventListener NO_EVENTS = ignored -> {};

  @Test
  void execute_whenRequiredPreflightFails_doesNotReachAnyMutation() {
    var delegate = new RecordingOrchestrator();
    PrivilegePreflight denied =
        () -> {
          throw new ShellExecutionException("sudo unavailable");
        };
    var orchestrator =
        new PolicyPrivilegeOrchestrator(delegate, new OncePrivilegeGate(denied, () -> {}));

    assertThatThrownBy(() -> orchestrator.execute(config(true), NO_EVENTS))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("sudo unavailable");
    assertThat(delegate.executeCalls).isZero();
  }

  @Test
  void execute_whenSudoIsNotRequired_skipsPreflight() {
    var delegate = new RecordingOrchestrator();
    PrivilegePreflight unexpected =
        () -> {
          throw new AssertionError("preflight should not run");
        };

    new PolicyPrivilegeOrchestrator(delegate, new OncePrivilegeGate(unexpected, () -> {}))
        .execute(config(false), NO_EVENTS);

    assertThat(delegate.executeCalls).isOne();
  }

  @Test
  void dryRun_whenSudoIsRequired_neverRunsPreflight() {
    var delegate = new RecordingOrchestrator();
    PrivilegePreflight unexpected =
        () -> {
          throw new AssertionError("dry-run must not authenticate");
        };

    new PolicyPrivilegeOrchestrator(delegate, new OncePrivilegeGate(unexpected, () -> {}))
        .dryRun(config(true), NO_EVENTS);

    assertThat(delegate.dryRunCalls).isOne();
  }

  @Test
  void execute_afterExplicitGateVerification_doesNotAuthenticateTwice() {
    var calls = new AtomicInteger();
    var gate = new OncePrivilegeGate(calls::incrementAndGet, () -> {});
    BootstrapConfig config = config(true);
    gate.verify(config);

    new PolicyPrivilegeOrchestrator(new RecordingOrchestrator(), gate).execute(config, NO_EVENTS);

    assertThat(calls).hasValue(1);
  }

  @Test
  void execute_whenEffectiveUserIsRoot_rejectsBeforeAnyMutation() {
    var delegate = new RecordingOrchestrator();
    var gate =
        new OncePrivilegeGate(
            () -> {},
            () -> {
              throw new ShellExecutionException("Refusing live apply as root");
            });

    assertThatThrownBy(
            () -> new PolicyPrivilegeOrchestrator(delegate, gate).execute(config(false), NO_EVENTS))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("as root");
    assertThat(delegate.executeCalls).isZero();
  }

  @Test
  void execute_whenEffectiveUidIsUnavailableAndSudoIsRequired_rejectsBeforePreflight() {
    var delegate = new RecordingOrchestrator();
    var preflightCalls = new AtomicInteger();
    var gate =
        new OncePrivilegeGate(
            preflightCalls::incrementAndGet, () -> RootExecutionGuard.verify(OptionalLong.empty()));

    assertThatThrownBy(
            () -> new PolicyPrivilegeOrchestrator(delegate, gate).execute(config(true), NO_EVENTS))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("effective UID");
    assertThat(preflightCalls).hasValue(0);
    assertThat(delegate.executeCalls).isZero();
  }

  @Test
  void execute_whenEffectiveUidIsUnavailableAndSudoIsNotRequired_rejectsBeforeMutation() {
    var delegate = new RecordingOrchestrator();
    var gate =
        new OncePrivilegeGate(
            () -> {
              throw new AssertionError("preflight should not run");
            },
            () -> RootExecutionGuard.verify(OptionalLong.empty()));

    assertThatThrownBy(
            () -> new PolicyPrivilegeOrchestrator(delegate, gate).execute(config(false), NO_EVENTS))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("effective UID");
    assertThat(delegate.executeCalls).isZero();
  }

  private BootstrapConfig config(boolean requireSudo) {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("test"))
        .target(new OsTarget.FedoraTarget("44"))
        .policy(new BootstrapPolicy(Optional.empty(), Optional.empty(), Optional.of(requireSudo)))
        .addModule(
            new ManualModule(
                new ModuleName("fixture"), "Privilege policy fixture", Optional.empty()))
        .build();
  }

  private static final class RecordingOrchestrator implements BootstrapOrchestrator {

    private int executeCalls;
    private int dryRunCalls;

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      executeCalls++;
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      dryRunCalls++;
    }
  }
}
