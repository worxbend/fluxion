package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.JsonStateRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Commands for inspecting and pruning fluxion's per-profile state files. */
@Command(
    name = "state",
    description = "Manage the fluxion state file",
    subcommands = {
      StateCommand.ShowSubcommand.class,
      StateCommand.ResetSubcommand.class,
      StateCommand.ForgetSubcommand.class,
      StateCommand.PathSubcommand.class
    })
public final class StateCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Override
  public void run() {
    System.out.println("Run 'fluxion state --help' for subcommands.");
  }

  @Command(name = "show", description = "Print all entries in the state file for a profile")
  public static final class ShowSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Parameters(index = "0", description = "Profile name", defaultValue = "default")
    private String profile;

    @Option(
        names = "--format",
        defaultValue = "text",
        description = "Output format: ${COMPLETION-CANDIDATES}")
    private OutputFormat format;

    @Override
    public void run() {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.load(profile)
          .ifPresentOrElse(
              state -> {
                if (format == OutputFormat.JSON) {
                  JsonOutput.write(spec.commandLine().getOut(), jsonState(state));
                  return;
                }
                var out = spec.commandLine().getOut();
                out.printf("Profile: %s  (last run: %s)%n", state.profileName(), state.lastRunAt());
                out.println();
                out.println("Phases:");
                if (state.phaseEntries().isEmpty()) {
                  out.println("  (none)");
                } else {
                  state
                      .phaseEntries()
                      .forEach(
                          e ->
                              out.printf(
                                  "  %-30s  %-10s  %s%n",
                                  e.phaseName(), e.status(), e.completedAt()));
                }
                out.println();
                out.println("Items:");
                if (state.entries().isEmpty()) {
                  out.println("  (none)");
                } else {
                  state
                      .entries()
                      .forEach(
                          e ->
                              out.printf(
                                  "  %-40s  %-15s  %s%n",
                                  e.itemKey(), e.itemType(), e.completedAt()));
                }
              },
              () -> writeMissingState());
    }

    private void writeMissingState() {
      if (format == OutputFormat.JSON) {
        JsonOutput.write(spec.commandLine().getOut(), jsonMissingState());
        return;
      }
      spec.commandLine().getOut().println("No state file found for profile: " + profile);
    }

    private Map<String, Object> jsonMissingState() {
      var output = new LinkedHashMap<String, Object>();
      output.put("profileName", profile);
      output.put("lastRunAt", null);
      output.put("phases", List.of());
      output.put("items", List.of());
      return output;
    }

    private Map<String, Object> jsonState(BootstrapState state) {
      var output = new LinkedHashMap<String, Object>();
      output.put("profileName", state.profileName());
      output.put("lastRunAt", state.lastRunAt().toString());
      output.put("phases", state.phaseEntries().stream().map(this::jsonPhase).toList());
      output.put("items", state.entries().stream().map(this::jsonItem).toList());
      return output;
    }

    private Map<String, Object> jsonPhase(PhaseStateEntry phase) {
      var output = new LinkedHashMap<String, Object>();
      output.put("name", phase.phaseName());
      output.put("status", phase.status().name().toLowerCase());
      output.put("completedAt", phase.completedAt().toString());
      output.put("fingerprint", phase.fingerprint().orElse(null));
      return output;
    }

    private Map<String, Object> jsonItem(StateEntry entry) {
      var output = new LinkedHashMap<String, Object>();
      output.put("moduleName", entry.moduleName());
      output.put("key", entry.itemKey());
      output.put("type", entry.itemType().name().toLowerCase());
      output.put("completedAt", entry.completedAt().toString());
      output.put("version", entry.version().orElse(null));
      output.put("checksum", entry.checksum().orElse(null));
      return output;
    }
  }

  @Command(name = "reset", description = "Delete the entire state file for a profile")
  public static final class ResetSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Parameters(index = "0", description = "Profile name", defaultValue = "default")
    private String profile;

    @Option(
        names = {"--force"},
        description = "Skip confirmation prompt")
    private boolean force;

    @Override
    public void run() {
      var repo = new JsonStateRepository(new ObjectMapper());
      Path stateFile = repo.path(profile);
      Path legacyStateFile = repo.legacyPath(profile);
      if (!Files.exists(stateFile) && !Files.exists(legacyStateFile)) {
        spec.commandLine().getOut().println("No state file found for profile: " + profile);
        return;
      }
      if (!force) {
        System.out.print("Delete state for profile '" + profile + "'? [y/N] ");
        String response = new java.util.Scanner(System.in).nextLine().strip();
        if (!response.equalsIgnoreCase("y")) {
          System.out.println("Aborted.");
          return;
        }
      }
      repo.reset(profile);
      spec.commandLine().getOut().println("State reset for profile: " + profile);
    }
  }

  @Command(name = "forget", description = "Remove a phase or item entry from the state file")
  public static final class ForgetSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Option(
        names = {"--profile"},
        required = true,
        description = "Profile name")
    private String profile;

    @Option(
        names = {"--phase"},
        description = "Phase name to forget")
    private String phaseName;

    @Option(
        names = {"--item"},
        description = "Item key to forget")
    private String itemKey;

    @Override
    public void run() {
      if (phaseName == null && itemKey == null) {
        throw new CliFailureException(ExitCode.INVALID_INPUT, "Specify --phase or --item");
      }
      var mapper = new ObjectMapper();
      var repo = new JsonStateRepository(mapper);
      if (repo.load(profile).isEmpty()) {
        spec.commandLine().getOut().println("No state file found for profile: " + profile);
        return;
      }
      if (phaseName != null) {
        repo.forgetPhase(profile, phaseName);
        spec.commandLine()
            .getOut()
            .printf("Forgot phase '%s' from profile '%s'%n", phaseName, profile);
      }
      if (itemKey != null) {
        repo.forgetItem(profile, itemKey);
        spec.commandLine()
            .getOut()
            .printf("Forgot item '%s' from profile '%s'%n", itemKey, profile);
      }
    }
  }

  @Command(name = "path", description = "Print path to the state file")
  public static final class PathSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Parameters(index = "0", description = "Profile name", defaultValue = "default")
    private String profile;

    @Override
    public void run() {
      var repo = new JsonStateRepository(new ObjectMapper());
      spec.commandLine().getOut().println(repo.path(profile).toAbsolutePath());
    }
  }
}
