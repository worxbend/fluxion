package dev.sysboot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ToolSpec;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * These assert on facts that hold whether or not the delegated tools happen to be installed on the
 * machine running the build — the earlier lesson that a host-dependent test can pass while checking
 * nothing applies squarely here, since this developer's PATH already carries all four tools.
 */
class ToolsCommandTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void listNamesEveryCatalogToolWithItsRepositoryAndPinnedVersion() {
    CliResult result = execute("tools", "list");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stderr()).isEmpty();
    for (ToolSpec tool : KnownTools.all()) {
      assertThat(result.stdout()).contains(tool.version()).contains(tool.executableName());
    }
    assertThat(result.stdout()).contains("dotbot-go").contains("dotbot-scala");
  }

  @Test
  @SuppressWarnings("unchecked")
  void listJsonReportsWhereEachToolWouldComeFrom() throws IOException {
    CliResult result = execute("tools", "list", "--format", "json");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    Map<String, Object> parsed = objectMapper.readValue(result.stdout(), Map.class);
    assertThat(parsed).containsKey("platform");
    var tools = (List<Map<String, Object>>) parsed.get("tools");

    assertThat(tools).hasSameSizeAs(KnownTools.all());
    assertThat(tools)
        .allSatisfy(
            tool -> {
              assertThat((String) tool.get("source")).isIn("path", "cache", "download");
              assertThat((List<String>) tool.get("assetCandidates")).isNotEmpty();
              assertThat((String) tool.get("path")).isNotBlank();
            });
  }

  @Test
  void theTwoDotbotBackendsAreSelectableSeparatelyEvenThoughBothInstallDotbot() {
    CliResult result = execute("tools", "list", "--format", "json");

    assertThat(result.stdout()).contains("\"tool\":\"dotbot-go\"");
    assertThat(result.stdout()).contains("\"tool\":\"dotbot-scala\"");
    assertThat(KnownTools.DOTBOT_GO.executableName())
        .isEqualTo(KnownTools.DOTBOT_SCALA.executableName());
  }

  @Test
  void installingAnUnknownToolFailsWithTheKnownNames() {
    CliResult result = execute("tools", "install", "homebrew");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Unknown tool 'homebrew'").contains("binstaller");
  }

  @Test
  void installRejectsTraversalVersionBeforeCacheOrDownloadWork() {
    CliResult result = execute("tools", "install", "dotbot-go", "--release", "../escape");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Invalid tool version").contains("traversal");
  }

  @Test
  void installRejectsUncataloguedVersionBeforeCacheOrDownloadWork() {
    CliResult result = execute("tools", "install", "dotbot-go", "--release", "v9.9.9");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr())
        .contains("Invalid tool version")
        .contains("trusted release-digest catalog");
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
