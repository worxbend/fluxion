package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemSettingModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SystemSettingExecutorTest {

  @Test
  void localeProbe_requiresExactAssignmentValue() {
    var runner = new LocaleRunner("System Locale: LANG=en_US.UTF-8\n");
    var module = locale("LANG", "en_US");

    StepResult result = new SystemSettingExecutor(runner).executeItem(module, "locale:LANG");

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).contains("sudo localectl set-locale LANG=en_US");
  }

  @Test
  void localeProbe_doesNotMatchInsideLongerKey() {
    var runner = new LocaleRunner("System Locale: FOO_LANG=en_US\n");
    var module = locale("LANG", "en_US");

    new SystemSettingExecutor(runner).executeItem(module, "locale:LANG");

    assertThat(runner.commands).contains("sudo localectl set-locale LANG=en_US");
  }

  @Test
  void localeProbe_acceptsExactAssignmentToken() {
    var runner = new LocaleRunner("System Locale: LANG=en_US\n");
    var module = locale("LANG", "en_US");

    new SystemSettingExecutor(runner).executeItem(module, "locale:LANG");

    assertThat(runner.commands).doesNotContain("sudo localectl set-locale LANG=en_US");
  }

  private static SystemSettingModule locale(String key, String value) {
    return new SystemSettingModule(
        new ModuleName("locale"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(key, value),
        false);
  }

  private static final class LocaleRunner implements ShellRunner {
    private final String status;
    private final List<String> commands = new ArrayList<>();

    private LocaleRunner(String status) {
      this.status = status;
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(String.join(" ", command));
      String stdout = command.equals(List.of("localectl", "status")) ? status : "";
      return new ProcessResult(0, stdout, "", Duration.ZERO);
    }
  }
}
