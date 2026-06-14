package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.executor.CyclicDependencyException;
import dev.sysboot.executor.PhaseExecutionPlanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** Validates that a YAML profile can be parsed and planned before any changes are made. */
@Command(name = "validate", description = "Validate a config file")
public final class ValidateCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    try {
      new PhaseExecutionPlanner().plan(config.phases());
    } catch (CyclicDependencyException e) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Cycle in job dependency graph: " + e.getMessage(), e);
    } catch (IllegalArgumentException e) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Invalid job dependency: " + e.getMessage(), e);
    }

    System.out.printf(
        "✓ Config is valid: profile '%s' with %d job(s), %d step(s)%n",
        config.profileName().value(), config.phases().size(), config.modules().size());
  }
}
