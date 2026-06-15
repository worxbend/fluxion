package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.StdoutExecutionEventListener;
import dev.sysboot.core.BootstrapConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "dry-run", description = "Show what would be executed without making any changes")
public final class DryRunCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    var listener = new StdoutExecutionEventListener();
    context.orchestrator().dryRun(config, listener);
  }
}
