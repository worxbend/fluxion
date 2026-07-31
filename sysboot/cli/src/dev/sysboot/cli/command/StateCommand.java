package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PublicUrl;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Mixin private GlobalOptions options;

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
                Optional<String> nextPhase = nextIncompletePhase(state);
                if (format == OutputFormat.JSON) {
                  JsonOutput.write(spec.commandLine().getOut(), jsonState(state, nextPhase));
                  return;
                }
                var out = spec.commandLine().getOut();
                out.printf("Profile: %s  (last run: %s)%n", state.profileName(), state.lastRunAt());
                nextPhase.ifPresent(phase -> out.printf("Next phase: %s%n", phase));
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
                                  "  %-24s  %-40s  %-15s  %s%n",
                                  e.moduleName(), e.itemKey(), e.itemType(), e.completedAt()));
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
      output.put("nextPhase", null);
      output.put("phases", List.of());
      output.put("items", List.of());
      return output;
    }

    private Map<String, Object> jsonState(BootstrapState state, Optional<String> nextPhase) {
      var output = new LinkedHashMap<String, Object>();
      output.put("profileName", state.profileName());
      output.put("lastRunAt", state.lastRunAt().toString());
      output.put("nextPhase", nextPhase.orElse(null));
      output.put("phases", state.phaseEntries().stream().map(this::jsonPhase).toList());
      output.put("items", state.entries().stream().map(this::jsonItem).toList());
      return output;
    }

    private Optional<String> nextIncompletePhase(BootstrapState state) {
      if (!options.hasConfigFile() || !Files.isReadable(options.resolvedConfigFile())) {
        return Optional.empty();
      }
      BootstrapConfig config =
          ApplicationContext.create(true).configLoader().load(options.resolvedConfigFile());
      return new PhaseExecutionPlanner()
          .plan(config.phases()).stream()
              .filter(phase -> !state.isPhaseCompleted(phase.name().value()))
              .map(phase -> phase.name().value())
              .findFirst();
    }

    private Map<String, Object> jsonPhase(PhaseStateEntry phase) {
      var output = new LinkedHashMap<String, Object>();
      output.put("name", phase.phaseName());
      output.put("status", phase.status().name().toLowerCase());
      output.put("completedAt", phase.completedAt().toString());
      output.put("fingerprint", phase.fingerprint().orElse(null));
      output.put("reason", phase.reason().orElse(null));
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
      output.put("sourceUrl", entry.sourceUrl().map(PublicUrl::from).orElse(null));
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
      if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)
          && !Files.exists(legacyStateFile, LinkOption.NOFOLLOW_LINKS)) {
        spec.commandLine().getOut().println("No state file found for profile: " + profile);
        return;
      }
      if (!force) {
        System.out.print("Delete state for profile '" + profile + "'? [y/N] ");
        String response =
            new java.util.Scanner(System.in, StandardCharsets.UTF_8).nextLine().strip();
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

    @Option(
        names = {"--module"},
        description = "Module name qualifying --item")
    private String moduleName;

    @Option(
        names = {"--type"},
        description = "Item type qualifying --item")
    private String itemType;

    @Override
    public void run() {
      if (phaseName == null && itemKey == null) {
        throw new CliFailureException(ExitCode.INVALID_INPUT, "Specify --phase or --item");
      }
      if (itemKey == null && (moduleName != null || itemType != null)) {
        throw new CliFailureException(ExitCode.INVALID_INPUT, "--module and --type require --item");
      }
      var mapper = new ObjectMapper();
      var repo = new JsonStateRepository(mapper);
      Optional<BootstrapState> loaded = repo.load(profile);
      if (loaded.isEmpty()) {
        spec.commandLine().getOut().println("No state file found for profile: " + profile);
        return;
      }
      Optional<StateEntry> selectedItem =
          itemKey == null ? Optional.empty() : Optional.of(resolveItem(loaded.orElseThrow()));
      if (phaseName != null) {
        repo.forgetPhase(profile, phaseName);
        spec.commandLine()
            .getOut()
            .printf("Forgot phase '%s' from profile '%s'%n", phaseName, profile);
      }
      if (selectedItem.isPresent()) {
        StateEntry item = selectedItem.orElseThrow();
        repo.forgetItem(
            profile, new ModuleName(item.moduleName()), item.itemKey(), item.itemType());
        spec.commandLine()
            .getOut()
            .printf(
                "Forgot item '%s/%s' (%s) from profile '%s'%n",
                item.moduleName(), item.itemKey(), item.itemType(), profile);
      }
    }

    private StateEntry resolveItem(BootstrapState state) {
      Optional<ItemType> expectedType = parseItemType();
      List<StateEntry> matches =
          state.entries().stream()
              .filter(entry -> entry.itemKey().equals(itemKey))
              .filter(entry -> moduleName == null || entry.moduleName().equals(moduleName))
              .filter(
                  entry -> expectedType.isEmpty() || entry.itemType() == expectedType.orElseThrow())
              .toList();
      if (matches.isEmpty()) {
        throw new CliFailureException(
            ExitCode.INVALID_INPUT, "No matching state item found: " + itemKey);
      }
      if (matches.size() > 1) {
        throw new CliFailureException(
            ExitCode.INVALID_INPUT,
            "Item key '%s' is ambiguous; qualify it with --module and --type".formatted(itemKey));
      }
      return matches.getFirst();
    }

    private Optional<ItemType> parseItemType() {
      if (itemType == null) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            ItemType.valueOf(itemType.replace('-', '_').toUpperCase(java.util.Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new CliFailureException(ExitCode.INVALID_INPUT, "Unknown item type: " + itemType, e);
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
