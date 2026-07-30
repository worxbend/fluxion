package dev.sysboot.cli.command;

import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.config.PlanKindCatalog;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists the plan kinds a WorkstationProfile may use.
 *
 * <p>Needs no config file: the answer is a property of this build, not of the user's profile. It
 * reads the same registry the validator does, so it cannot drift from what will actually be
 * accepted.
 */
@Command(name = "kinds", description = "List the plan kinds a WorkstationProfile can use")
public final class KindsCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Override
  public void run() {
    List<PlanKindCatalog.Entry> entries = PlanKindCatalog.entries();
    PrintWriter out = spec.commandLine().getOut();

    if (format == OutputFormat.JSON) {
      var document = new LinkedHashMap<String, Object>();
      document.put("kinds", entries.stream().map(KindsCommand::json).toList());
      JsonOutput.write(out, document);
      return;
    }

    int idWidth = entries.stream().mapToInt(entry -> entry.id().length()).max().orElse(4);
    out.printf("%-" + idWidth + "s  %s%n", "KIND", "DESCRIPTION");
    out.println("-".repeat(idWidth + 2 + 60));
    for (PlanKindCatalog.Entry entry : entries) {
      out.printf("%-" + idWidth + "s  %s%n", entry.id(), entry.summary());
      if (!entry.packageActions().isEmpty()) {
        out.printf(
            "%-" + idWidth + "s  actions: %s%n", "", String.join(", ", entry.packageActions()));
      }
    }
    out.println();
    out.println("Reference: sysboot/docs/workstation-profile.md");
  }

  private static Map<String, Object> json(PlanKindCatalog.Entry entry) {
    var output = new LinkedHashMap<String, Object>();
    output.put("id", entry.id());
    output.put("summary", entry.summary());
    output.put("category", entry.category());
    output.put("packageActions", entry.packageActions());
    return output;
  }
}
