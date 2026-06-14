package dev.sysboot.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Root command for Linux system bootstrapping from declarative YAML profiles.
 *
 * <p>The command itself only prints usage guidance; subcommands perform validation, planning, state
 * management, and execution.
 */
@Command(
    name = "sysboot",
    mixinStandardHelpOptions = true,
    version = "sysboot 1.0.0",
    description = "Bootstrap your Linux system from a declarative YAML config",
    subcommands = {
      RunCommand.class,
      DryRunCommand.class,
      ValidateCommand.class,
      PlanCommand.class,
      ListCommand.class,
      StatusCommand.class,
      StateCommand.class
    })
public final class SysbootCommand implements Runnable {

  @Mixin private GlobalOptions globalOptions;

  @Override
  public void run() {
    System.out.println("Run 'sysboot --help' for usage.");
  }
}
