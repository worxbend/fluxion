package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.PlainExecutionReport;
import dev.sysboot.cli.output.StdoutExecutionEventListener;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.executor.ExecutionPlan;
import dev.sysboot.executor.JsonStateRepository;
import java.io.PrintWriter;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "dry-run", description = "Show what would be executed without making any changes")
public final class DryRunCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Option(
      names = {"--profile"},
      defaultValue = "default",
      paramLabel = "PROFILE")
  private String profile;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    ExecutionPlan plan = buildPlan(context, config);
    var statePath = new JsonStateRepository(new ObjectMapper()).path(profile);
    PlainExecutionReport.writeHeader(
        new PrintWriter(System.out, true),
        "dry-run",
        "dry-run",
        plan.profileName(),
        context.hostFactsProvider().facts(),
        Optional.of(statePath));
    PlainExecutionReport.writeWorkstationSelection(new PrintWriter(System.out, true), plan);
    var listener =
        new StdoutExecutionEventListener(event -> Optional.empty(), () -> Optional.of(statePath));
    context.orchestrator().dryRun(config, listener);
    listener.printSummary();
  }

  private ExecutionPlan buildPlan(ApplicationContext context, BootstrapConfig config) {
    try {
      return context.executionPlanBuilder().build(config);
    } catch (dev.sysboot.executor.CyclicDependencyException e) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Cycle detected: " + e.getMessage(), e);
    }
  }
}
