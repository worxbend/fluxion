package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PtyShellRunnerTest {

  @Test
  void privilegedEffect_usesTrustedNonInteractiveSudoWithOptionSeparator() {
    List<String> command = SudoCommand.forEffect(List.of("sudo", "touch", "/tmp/example"));

    assertThat(Path.of(command.getFirst())).isAbsolute();
    assertThat(Path.of(command.get(3))).isAbsolute();
    assertThat(command)
        .containsExactly(
            SudoCommand.executable(),
            "-n",
            "--",
            TrustedSystemExecutable.resolve("touch").toString(),
            "/tmp/example");
  }

  @Test
  void privilegedEffect_revalidatesAlreadyHardenedTargets() {
    List<String> command =
        SudoCommand.forEffect(
            List.of(
                SudoCommand.executable(),
                "-n",
                "--",
                TrustedSystemExecutable.resolve("touch").toString(),
                "/tmp/example"));

    assertThat(command.get(3)).isEqualTo(TrustedSystemExecutable.resolve("touch").toString());
  }

  @Test
  void privilegedEffect_rejectsRelativeAndUntrustedAbsoluteTargets() {
    assertThatThrownBy(() -> SudoCommand.forEffect(List.of("sudo", "./touch", "/tmp/example")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bare name");
    assertThatThrownBy(() -> SudoCommand.forEffect(List.of("sudo", "/tmp/touch", "/tmp/example")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trusted system executable");
  }
}
