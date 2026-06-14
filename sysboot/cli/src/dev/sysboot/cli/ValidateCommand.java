package dev.sysboot.cli;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.config.ConfigLoadException;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.executor.CyclicDependencyException;
import dev.sysboot.executor.PhaseExecutionPlanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "validate", description = "Validate a config file")
public final class ValidateCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    BootstrapConfig config;
    try {
      config = context.configLoader().load(options.resolvedConfigFile());
    } catch (ConfigLoadException e) {
      System.err.println("✗ Config parse error: " + e.getMessage());
      throw new picocli.CommandLine.ParameterException(
          new picocli.CommandLine(this), e.getMessage());
    }

    try {
      new PhaseExecutionPlanner().plan(config.phases());
    } catch (CyclicDependencyException e) {
      System.err.println("✗ Cycle in job dependency graph: " + e.getMessage());
      throw new picocli.CommandLine.ParameterException(
          new picocli.CommandLine(this), e.getMessage());
    } catch (IllegalArgumentException e) {
      System.err.println("✗ Invalid job dependency: " + e.getMessage());
      throw new picocli.CommandLine.ParameterException(
          new picocli.CommandLine(this), e.getMessage());
    }

    System.out.printf(
        "✓ Config is valid: profile '%s' with %d job(s), %d step(s)%n",
        config.profileName().value(), config.phases().size(), config.modules().size());
  }
}
