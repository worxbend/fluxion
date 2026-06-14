package dev.sysboot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.executor.JsonStateRepository;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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
  static final class ShowSubcommand implements Runnable {

    @Parameters(index = "0", description = "Profile name", defaultValue = "default")
    private String profile;

    @Override
    public void run() {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.load(profile)
          .ifPresentOrElse(
              state -> {
                System.out.printf(
                    "Profile: %s  (last run: %s)%n", state.profileName(), state.lastRunAt());
                System.out.println();
                System.out.println("Phases:");
                if (state.phaseEntries().isEmpty()) {
                  System.out.println("  (none)");
                } else {
                  state
                      .phaseEntries()
                      .forEach(
                          e ->
                              System.out.printf(
                                  "  %-30s  %-10s  %s%n",
                                  e.phaseName(), e.status(), e.completedAt()));
                }
                System.out.println();
                System.out.println("Items:");
                if (state.entries().isEmpty()) {
                  System.out.println("  (none)");
                } else {
                  state
                      .entries()
                      .forEach(
                          e ->
                              System.out.printf(
                                  "  %-40s  %-15s  %s%n",
                                  e.itemKey(), e.itemType(), e.completedAt()));
                }
              },
              () -> System.out.println("No state file found for profile: " + profile));
    }
  }

  @Command(name = "reset", description = "Delete the entire state file for a profile")
  static final class ResetSubcommand implements Runnable {

    @Parameters(index = "0", description = "Profile name", defaultValue = "default")
    private String profile;

    @Option(
        names = {"--force"},
        description = "Skip confirmation prompt")
    private boolean force;

    @Override
    public void run() {
      Path stateFile =
          Path.of(System.getProperty("user.home"))
              .resolve(".local/share/fluxion")
              .resolve(profile + ".state.json");
      if (!stateFile.toFile().exists()) {
        System.out.println("No state file found for profile: " + profile);
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
      if (!stateFile.toFile().delete()) {
        throw new CliFailureException(ExitCode.IO_ERROR, "Failed to delete: " + stateFile);
      }
      System.out.println("State reset for profile: " + profile);
    }
  }

  @Command(name = "forget", description = "Remove a phase or item entry from the state file")
  static final class ForgetSubcommand implements Runnable {

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
      repo.load(profile)
          .ifPresentOrElse(
              state -> {
                BootstrapState updated = state;
                if (phaseName != null) {
                  updated =
                      new BootstrapState(
                          state.profileName(),
                          state.lastRunAt(),
                          state.sysbootVersion(),
                          state.entries(),
                          state.phaseEntries().stream()
                              .filter(e -> !e.phaseName().equals(phaseName))
                              .toList());
                  System.out.printf("Forgot phase '%s' from profile '%s'%n", phaseName, profile);
                }
                if (itemKey != null) {
                  updated =
                      new BootstrapState(
                          state.profileName(),
                          state.lastRunAt(),
                          state.sysbootVersion(),
                          state.entries().stream()
                              .filter(e -> !e.itemKey().equals(itemKey))
                              .toList(),
                          state.phaseEntries());
                  System.out.printf("Forgot item '%s' from profile '%s'%n", itemKey, profile);
                }
                repo.save(updated);
              },
              () -> System.out.println("No state file found for profile: " + profile));
    }
  }

  @Command(name = "path", description = "Print path to the state file")
  static final class PathSubcommand implements Runnable {

    @Parameters(index = "0", description = "Profile name", defaultValue = "default")
    private String profile;

    @Override
    public void run() {
      Path stateFile =
          Path.of(System.getProperty("user.home"))
              .resolve(".local/share/fluxion")
              .resolve(profile + ".state.json");
      System.out.println(stateFile.toAbsolutePath());
    }
  }
}
