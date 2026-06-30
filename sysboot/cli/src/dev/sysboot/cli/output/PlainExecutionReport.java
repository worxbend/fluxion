package dev.sysboot.cli.output;

import dev.sysboot.core.HostFacts;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.executor.ExecutionPlan;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PlainExecutionReport {

  private PlainExecutionReport() {}

  public static void writeHeader(
      PrintWriter out,
      String operation,
      String mode,
      String profileName,
      HostFacts hostFacts,
      Optional<Path> statePath) {
    out.println("Operation: " + operation);
    out.println("Mode: " + mode);
    out.println("Manifest/Profile: " + profileName);
    out.println("Host: " + hostFacts(hostFacts));
    statePath.ifPresent(path -> out.println("State: " + path));
  }

  public static void writeWorkstationSelection(PrintWriter out, ExecutionPlan plan) {
    List<ExecutionPlan.Module> selectedEntries = selectedEntries(plan);
    writeSourceSetups(out, plan.sourceSetups());
    writeSelectedEntries(out, selectedEntries);
    writeSkippedEntries(out, plan.skippedEntries());
    out.printf(
        "Planned counts: source_setups=%d selected=%d skipped=%d items=%d%n",
        plan.sourceSetups().size(),
        selectedEntries.size(),
        plan.skippedEntries().size(),
        itemCount(plan));
    out.println();
  }

  public static void writePlanFinalCounts(PrintWriter out, ExecutionPlan plan) {
    out.printf(
        "Final counts: source_setups=%d selected=%d skipped=%d items=%d%n",
        plan.sourceSetups().size(),
        selectedEntries(plan).size(),
        plan.skippedEntries().size(),
        itemCount(plan));
  }

  private static void writeSourceSetups(PrintWriter out, List<ExecutionPlan.Module> entries) {
    if (entries.isEmpty()) {
      return;
    }
    out.println("Source setup entries:");
    for (ExecutionPlan.Module entry : entries) {
      out.printf("  - %s type=%s items=%s%n", entry.name(), entry.type(), items(entry));
    }
  }

  private static void writeSelectedEntries(PrintWriter out, List<ExecutionPlan.Module> entries) {
    if (entries.isEmpty()) {
      return;
    }
    out.println("Selected WorkstationProfile entries:");
    for (ExecutionPlan.Module entry : entries) {
      out.printf("  - %s type=%s items=%s%n", entry.name(), entry.type(), items(entry));
    }
  }

  private static void writeSkippedEntries(PrintWriter out, List<SkippedPlanEntry> entries) {
    if (entries.isEmpty()) {
      return;
    }
    out.println("Skipped WorkstationProfile entries:");
    for (SkippedPlanEntry entry : entries) {
      out.printf("  - %s type=%s reason=%s%n", entry.name(), entry.kind(), entry.reason());
    }
  }

  private static List<ExecutionPlan.Module> selectedEntries(ExecutionPlan plan) {
    return plan.phases().stream()
        .filter(phase -> phase.name().equals("manifest-plan"))
        .flatMap(phase -> phase.modules().stream())
        .toList();
  }

  private static int itemCount(ExecutionPlan plan) {
    int sourceItems = plan.sourceSetups().stream().mapToInt(module -> module.items().size()).sum();
    int phaseItems =
        plan.phases().stream()
            .flatMap(phase -> phase.modules().stream())
            .mapToInt(module -> module.items().size())
            .sum();
    return sourceItems + phaseItems;
  }

  private static String items(ExecutionPlan.Module module) {
    return module.items().stream()
        .map(ExecutionPlan.Item::item)
        .map(ModuleItem::displayName)
        .collect(Collectors.joining(", "));
  }

  private static String hostFacts(HostFacts facts) {
    return "os="
        + facts.osFamily()
        + " distribution="
        + facts.distribution().orElse("unknown")
        + " version="
        + facts.version().orElse("unknown")
        + " codename="
        + facts.codename().orElse("unknown")
        + " arch="
        + facts.architecture();
  }
}
