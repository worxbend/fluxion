package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.executor.JsonStateRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

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
