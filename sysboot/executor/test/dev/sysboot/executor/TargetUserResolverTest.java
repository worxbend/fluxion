package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetUserResolverTest {

  @Test
  void invokingUser_whenRootAndSudoUserExists_usesSudoUser() {
    var host =
        new TargetUserResolver.HostContext(
            0L, Optional.of("alice"), "root", username -> "alice".equals(username));

    assertThat(TargetUserResolver.invokingUser(host)).isEqualTo("alice");
  }

  @Test
  void invokingUser_whenNotRoot_ignoresSpoofedSudoUser() {
    var host =
        new TargetUserResolver.HostContext(
            1000L, Optional.of("alice"), "developer", username -> true);

    assertThat(TargetUserResolver.invokingUser(host)).isEqualTo("developer");
  }

  @Test
  void invokingUser_whenSudoUserDoesNotExist_fallsBackToCurrentAccount() {
    var host =
        new TargetUserResolver.HostContext(0L, Optional.of("missing"), "root", username -> false);

    assertThat(TargetUserResolver.invokingUser(host)).isEqualTo("root");
  }

  @Test
  void effectiveUserIdentity_readsEffectiveRatherThanRealUid() {
    assertThat(
            EffectiveUserIdentity.parse(
                """
                Name: java
                Uid: 1000 0 1000 1000
                """))
        .hasValue(0L);
  }
}
