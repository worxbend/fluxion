package dev.sysboot.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.HostFacts;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.executor.ExecutionPlan;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlainExecutionReportTest {

  @Test
  void plainReport_whenFieldsContainControlsOrSecrets_sanitizesEveryDisplayField() {
    var output = new StringWriter();
    var writer = new PrintWriter(output, true);
    var facts =
        new HostFacts(
            "linux\u001B[2J",
            Optional.of("fedora\nFORGED"),
            Optional.of("44"),
            Optional.of("PASSWORD=hunter2"),
            "x86_64\u0007");

    PlainExecutionReport.writeHeader(
        writer,
        "apply\nFORGED",
        "live\u001B[31m",
        "profile\u001B]8;;https://evil.test\u0007",
        facts,
        Optional.of(Path.of("/tmp/state\nFORGED")));
    PlainExecutionReport.writeWorkstationSelection(writer, plan());

    assertThat(output.toString())
        .contains("Operation: apply FORGED")
        .contains("Manifest/Profile: profile")
        .contains("PASSWORD=<redacted>")
        .doesNotContain("\nFORGED", "\u001B", "\u0007", "hunter2", "https://evil.test");
  }

  private ExecutionPlan plan() {
    var source = module("source\nFORGED", "source\u001B[31m", "PASSWORD=hunter2");
    var selected = module("selected\u001B[2J", "manual\nFORGED", "item\u0007");
    return new ExecutionPlan(
        "profile",
        List.of(source),
        List.of(
            new ExecutionPlan.Phase(
                "manifest-plan", List.of(), ExecutionPlan.RestartEffect.NONE, List.of(selected))),
        List.of(new SkippedPlanEntry("skipped\nFORGED", "kind\u001B[31m", "PASSWORD=hunter2")));
  }

  private ExecutionPlan.Module module(String name, String type, String item) {
    var moduleName = new ModuleName(name);
    var moduleItem = new ModuleItem(moduleName, "key", item, ItemType.MANUAL, Optional.empty());
    return new ExecutionPlan.Module(
        name, type, List.of(new ExecutionPlan.Item(moduleItem, Optional.empty())));
  }
}
