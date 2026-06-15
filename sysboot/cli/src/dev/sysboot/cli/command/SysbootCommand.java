package dev.sysboot.cli.command;

import dev.sysboot.cli.option.GlobalOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Root command for Linux system bootstrapping from declarative YAML profiles.
 *
 * <p>The command itself only prints usage guidance; subcommands perform validation, planning, state
 * management, and execution.
 */
@Command(
    name = "fluxion",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    description = "Bootstrap your Linux system from a declarative YAML config",
    subcommands = {
      ApplyCommand.class,
      DryRunCommand.class,
      ValidateCommand.class,
      PlanCommand.class,
      DiffCommand.class,
      ExplainCommand.class,
      ListCommand.class,
      StatusCommand.class,
      StateCommand.class,
      GenerateCommand.class,
      SnapshotCommand.class,
      ImportCommand.class,
      DoctorCommand.class
    })
public final class SysbootCommand implements Runnable {

  @Mixin private GlobalOptions globalOptions;

  @Override
  public void run() {
    System.out.println("Run 'fluxion --help' for usage.");
  }
}
