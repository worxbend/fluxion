package dev.sysboot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.config.PlanKindCatalog;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class KindsCommandTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void kindsListsEverySupportedKindWithoutNeedingAConfigFile() {
    CliResult result = execute("kinds");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stderr()).isEmpty();
    for (PlanKindCatalog.Entry entry : PlanKindCatalog.entries()) {
      assertThat(result.stdout()).contains(entry.id()).contains(entry.summary());
    }
  }

  @Test
  void kindsShowsThePreInstallActionsAPackageKindAccepts() {
    CliResult result = execute("kinds");

    assertThat(result.stdout()).contains("actions: dist-upgrade, update, upgrade");
  }

  @Test
  @SuppressWarnings("unchecked")
  void jsonOutputCarriesTheIdSummaryCategoryAndActions() throws IOException {
    CliResult result = execute("kinds", "--format", "json");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    Map<String, Object> parsed = objectMapper.readValue(result.stdout(), Map.class);
    var kinds = (List<Map<String, Object>>) parsed.get("kinds");

    assertThat(kinds).hasSameSizeAs(PlanKindCatalog.entries());
    assertThat(kinds)
        .anySatisfy(
            kind -> {
              assertThat(kind).containsEntry("id", "dnf-packages");
              assertThat(kind).containsEntry("category", "packages");
              assertThat((List<String>) kind.get("packageActions")).contains("check-update");
            });
    assertThat(kinds)
        .anySatisfy(
            kind -> {
              assertThat(kind).containsEntry("id", "interrupt");
              assertThat(kind).containsEntry("category", "control");
              assertThat((List<String>) kind.get("packageActions")).isEmpty();
            });
  }

  private CliResult execute(String... args) {
    CommandLine commandLine = Main.commandLine();
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout));
    commandLine.setErr(new PrintWriter(stderr));

    int exitCode = commandLine.execute(args);

    return new CliResult(exitCode, stdout.toString(), stderr.toString());
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
