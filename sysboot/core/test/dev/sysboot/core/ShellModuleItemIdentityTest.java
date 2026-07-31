package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShellModuleItemIdentityTest {

  @Test
  void shellScriptModule_rejectsDuplicateItemNames() {
    var first =
        new ShellScriptItem(
            "duplicate",
            Optional.of(new ScriptPath(Path.of("./first.sh"))),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            List.of(),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            java.time.Duration.ofMinutes(1),
            Optional.empty());
    var second =
        new ShellScriptItem(
            "duplicate",
            Optional.of(new ScriptPath(Path.of("./second.sh"))),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            List.of(),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            java.time.Duration.ofMinutes(1),
            Optional.empty());

    assertThatThrownBy(
            () ->
                new ShellScriptModule(
                    new ModuleName("scripts"),
                    List.of(first, second),
                    Optional.empty(),
                    false,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be unique");
  }

  @Test
  void shellCommandModule_rejectsDuplicateItemNames() {
    var first = ShellCommandItem.shell("duplicate", "echo first", "/bin/sh", Optional.empty());
    var second = ShellCommandItem.shell("duplicate", "echo second", "/bin/sh", Optional.empty());

    assertThatThrownBy(
            () ->
                new ShellCommandModule(
                    new ModuleName("commands"),
                    List.of(first, second),
                    "/bin/sh",
                    Optional.empty(),
                    false,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be unique");
  }
}
