package dev.sysboot.tui;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.Phase;
import java.io.Console;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

final class TuiSelectionPrompt {

  private final LineReader reader;
  private final PrintStream out;
  private final BootstrapConfigSelectionFilter filter;

  TuiSelectionPrompt() {
    this(new ConsoleLineReader(), System.out, new BootstrapConfigSelectionFilter());
  }

  TuiSelectionPrompt(LineReader reader, PrintStream out, BootstrapConfigSelectionFilter filter) {
    this.reader = reader;
    this.out = out;
    this.filter = filter;
  }

  static TuiSelectionPrompt autoSelect() {
    return new TuiSelectionPrompt(
        new LineReader() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public String readLine(String prompt) {
            return "run";
          }
        },
        new PrintStream(OutputStream.nullOutputStream()),
        new BootstrapConfigSelectionFilter());
  }

  Optional<BootstrapConfig> select(BootstrapConfig config) {
    if (!reader.available()) {
      return Optional.of(config);
    }
    BootstrapSelection selection = BootstrapSelection.allSelected(config.phases());
    try {
      return selectJobs(config, selection).map(selected -> filter.apply(config, selected));
    } catch (SelectionAcceptedException e) {
      return Optional.of(filter.apply(config, selection));
    } catch (SelectionCancelledException e) {
      return Optional.empty();
    }
  }

  private Optional<BootstrapSelection> selectJobs(
      BootstrapConfig config, BootstrapSelection selection) {
    while (true) {
      renderJobs(config, selection);
      String command = reader.readLine("jobs> ");
      if (isRun(command)) {
        return Optional.of(selection);
      }
      if (isCancel(command)) {
        return Optional.empty();
      }
      handleJobCommand(config, selection, command);
    }
  }

  private void handleJobCommand(
      BootstrapConfig config, BootstrapSelection selection, String command) {
    ParsedCommand parsed = ParsedCommand.parse(command);
    if (parsed.is("j")) {
      phaseAt(config.phases(), parsed.index()).ifPresent(selection::togglePhase);
    } else if (parsed.is("s")) {
      phaseAt(config.phases(), parsed.index()).ifPresent(phase -> selectSteps(phase, selection));
    }
  }

  private void selectSteps(Phase phase, BootstrapSelection selection) {
    while (true) {
      renderSteps(phase, selection);
      String command = reader.readLine("steps> ");
      if (isBack(command)) {
        return;
      }
      if (isRun(command)) {
        throw new SelectionAcceptedException();
      }
      if (isCancel(command)) {
        throw new SelectionCancelledException();
      }
      handleStepCommand(phase, selection, command);
    }
  }

  private void handleStepCommand(Phase phase, BootstrapSelection selection, String command) {
    ParsedCommand parsed = ParsedCommand.parse(command);
    if (parsed.is("t")) {
      moduleAt(phase.modules(), parsed.index()).ifPresent(selection::toggleModule);
    } else if (parsed.is("e")) {
      moduleAt(phase.modules(), parsed.index())
          .ifPresent(module -> selectEntries(module, selection));
    }
  }

  private void selectEntries(BootstrapModule module, BootstrapSelection selection) {
    List<String> entries = SelectionEntryCatalog.entries(module);
    while (true) {
      renderEntries(module, entries, selection);
      String command = reader.readLine("entries> ");
      if (isBack(command)) {
        return;
      }
      if (isRun(command)) {
        throw new SelectionAcceptedException();
      }
      if (isCancel(command)) {
        throw new SelectionCancelledException();
      }
      ParsedCommand parsed = ParsedCommand.parse(command);
      if (parsed.is("t")) {
        entryAt(entries, parsed.index()).ifPresent(entry -> selection.toggleEntry(module, entry));
      }
    }
  }

  private void renderJobs(BootstrapConfig config, BootstrapSelection selection) {
    out.println();
    out.printf("Select jobs for profile '%s'%n", config.profileName().value());
    out.println("Commands: j N toggle job, s N select steps, run, q");
    for (int i = 0; i < config.phases().size(); i++) {
      Phase phase = config.phases().get(i);
      out.printf(
          "%2d. [%s] %s  (%d step(s))%n",
          i + 1,
          mark(selection.phaseSelected(phase)),
          phase.name().value(),
          phase.modules().size());
    }
  }

  private void renderSteps(Phase phase, BootstrapSelection selection) {
    out.println();
    out.printf("Job '%s' steps%n", phase.name().value());
    out.println("Commands: t N toggle step, e N select entries, b, run, q");
    for (int i = 0; i < phase.modules().size(); i++) {
      BootstrapModule module = phase.modules().get(i);
      out.printf(
          "%2d. [%s] %s  (%d entr%s)%n",
          i + 1,
          mark(selection.moduleSelected(module)),
          module.name().value(),
          SelectionEntryCatalog.entries(module).size(),
          SelectionEntryCatalog.entries(module).size() == 1 ? "y" : "ies");
    }
  }

  private void renderEntries(
      BootstrapModule module, List<String> entries, BootstrapSelection selection) {
    out.println();
    out.printf("Step '%s' entries%n", module.name().value());
    out.println("Commands: t N toggle entry, b, run, q");
    for (int i = 0; i < entries.size(); i++) {
      String entry = entries.get(i);
      out.printf("%2d. [%s] %s%n", i + 1, mark(selection.entrySelected(module, entry)), entry);
    }
  }

  private String mark(boolean selected) {
    return selected ? "x" : " ";
  }

  private boolean isRun(String command) {
    return command.isBlank() || command.equalsIgnoreCase("run");
  }

  private boolean isBack(String command) {
    return command.equalsIgnoreCase("b") || command.equalsIgnoreCase("back");
  }

  private boolean isCancel(String command) {
    return command.equalsIgnoreCase("q") || command.equalsIgnoreCase("quit");
  }

  private Optional<Phase> phaseAt(List<Phase> phases, int index) {
    return index > 0 && index <= phases.size()
        ? Optional.of(phases.get(index - 1))
        : Optional.empty();
  }

  private Optional<BootstrapModule> moduleAt(List<BootstrapModule> modules, int index) {
    return index > 0 && index <= modules.size()
        ? Optional.of(modules.get(index - 1))
        : Optional.empty();
  }

  private Optional<String> entryAt(List<String> entries, int index) {
    return index > 0 && index <= entries.size()
        ? Optional.of(entries.get(index - 1))
        : Optional.empty();
  }

  interface LineReader {
    boolean available();

    String readLine(String prompt);
  }

  private static final class ConsoleLineReader implements LineReader {

    @Override
    public boolean available() {
      return System.console() != null;
    }

    @Override
    public String readLine(String prompt) {
      Console console = System.console();
      return console == null ? "run" : console.readLine(prompt).strip();
    }
  }

  private record ParsedCommand(String name, int index) {

    static ParsedCommand parse(String command) {
      String[] parts = command.strip().split("\\s+", 2);
      if (parts.length < 2) {
        return new ParsedCommand(parts[0], -1);
      }
      try {
        return new ParsedCommand(parts[0], Integer.parseInt(parts[1]));
      } catch (NumberFormatException e) {
        return new ParsedCommand(parts[0], -1);
      }
    }

    boolean is(String expected) {
      return name.equalsIgnoreCase(expected);
    }
  }

  private static final class SelectionCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }

  private static final class SelectionAcceptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
