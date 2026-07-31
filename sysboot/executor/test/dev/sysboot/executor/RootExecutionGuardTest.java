package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RootExecutionGuardTest {

  @Test
  void verify_whenEffectiveUidStatusIsUnreadable_rejectsLiveApply() {
    OptionalLong effectiveUid =
        EffectiveUserIdentity.current(
            ignored -> {
              throw new IOException("unreadable");
            });

    assertUnknownIdentityRejected(effectiveUid);
  }

  @Test
  void verify_whenEffectiveUidStatusIsMalformed_rejectsLiveApply() {
    OptionalLong effectiveUid =
        EffectiveUserIdentity.parse(
            """
            Name: java
            Uid: 1000 invalid 1000 1000
            """);

    assertUnknownIdentityRejected(effectiveUid);
  }

  @Test
  void verify_whenEffectiveUidIsRoot_rejectsLiveApply() {
    assertThatThrownBy(() -> RootExecutionGuard.verify(OptionalLong.of(0L)))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("as root");
  }

  @Test
  void verify_whenEffectiveUidIsNonzero_allowsLiveApply() {
    assertThatCode(() -> RootExecutionGuard.verify(OptionalLong.of(1000L)))
        .doesNotThrowAnyException();
  }

  private void assertUnknownIdentityRejected(OptionalLong effectiveUid) {
    assertThatThrownBy(() -> RootExecutionGuard.verify(effectiveUid))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("effective UID");
  }
}
