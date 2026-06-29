package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.WhenDocument;
import dev.sysboot.core.HostFacts;
import dev.sysboot.core.HostFactsProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

final class WorkstationProfileWhenEvaluator {

  private final HostFactsProvider hostFactsProvider;

  WorkstationProfileWhenEvaluator(HostFactsProvider hostFactsProvider) {
    this.hostFactsProvider = Objects.requireNonNull(hostFactsProvider);
  }

  PlanSelection select(List<PlanEntryDocument> entries) {
    HostFacts facts = hostFactsProvider.facts();
    var selected = new ArrayList<PlanEntryDocument>();
    var skipped = new ArrayList<SkippedPlanEntry>();
    for (PlanEntryDocument entry : entries) {
      Decision decision = evaluate(entry.when(), facts);
      if (decision.matches()) {
        selected.add(entry);
      } else {
        skipped.add(new SkippedPlanEntry(planName(entry), planKind(entry), decision.reason()));
      }
    }
    return new PlanSelection(List.copyOf(selected), List.copyOf(skipped));
  }

  boolean matches(Optional<WhenDocument> when) {
    return evaluate(when, hostFactsProvider.facts()).matches();
  }

  private Decision evaluate(Optional<WhenDocument> when, HostFacts facts) {
    return when.map(value -> evaluate(value, facts)).orElseGet(Decision::selected);
  }

  private Decision evaluate(WhenDocument when, HostFacts facts) {
    Decision decision = scalarFacts(when, facts);
    if (!decision.matches()) {
      return decision;
    }
    decision = commands("commands", when.commandsCondition(), CommandMode.ALL);
    if (!decision.matches()) {
      return decision;
    }
    decision = commands("commandExists", when.commandExistsCondition(), CommandMode.ANY);
    if (!decision.matches()) {
      return decision;
    }
    return oneOf(when.oneOf(), facts);
  }

  private Decision scalarFacts(WhenDocument when, HostFacts facts) {
    return firstSkip(
            scalar("os", when.osCondition(), facts.osFamily()),
            scalar("osFamily", when.osFamilyCondition(), facts.osFamily()),
            scalar("distribution", when.distributionCondition(), facts.distribution()),
            scalar("distributions", when.distributionsCondition(), facts.distribution()),
            scalar("version", when.versionCondition(), facts.version()),
            scalar("codename", when.codenameCondition(), facts.codename()),
            scalar("architecture", when.architectureCondition(), facts.architecture()),
            scalar("architectures", when.architecturesCondition(), facts.architecture()))
        .orElseGet(Decision::selected);
  }

  private Optional<Decision> firstSkip(Decision... decisions) {
    for (Decision decision : decisions) {
      if (!decision.matches()) {
        return Optional.of(decision);
      }
    }
    return Optional.empty();
  }

  private Decision scalar(String label, Optional<JsonNode> node, Optional<String> actual) {
    return node.map(value -> scalar(label, value, actual)).orElseGet(Decision::selected);
  }

  private Decision scalar(String label, Optional<JsonNode> node, String actual) {
    return scalar(label, node, Optional.of(actual));
  }

  private Decision scalar(String label, JsonNode node, Optional<String> actual) {
    List<String> expected = matcherValues(node);
    if (expected.isEmpty()) {
      return Decision.skipped("when." + label + " has no supported matcher");
    }
    if (matchesAny(expected, actual)) {
      return Decision.selected();
    }
    return Decision.skipped(reason(label, expected, actual));
  }

  private Decision commands(String label, Optional<JsonNode> node, CommandMode mode) {
    return node.map(value -> commands(label, value, mode)).orElseGet(Decision::selected);
  }

  private Decision commands(String label, JsonNode node, CommandMode mode) {
    List<String> commands = matcherValues(node);
    if (commands.isEmpty()) {
      return Decision.skipped("when." + label + " has no supported matcher");
    }
    return switch (mode) {
      case ALL -> allCommands(label, commands);
      case ANY -> anyCommand(label, commands);
    };
  }

  private Decision allCommands(String label, List<String> commands) {
    for (String command : commands) {
      if (!hostFactsProvider.commandExists(command)) {
        return missingCommand(label, command);
      }
    }
    return Decision.selected();
  }

  private Decision anyCommand(String label, List<String> commands) {
    for (String command : commands) {
      if (hostFactsProvider.commandExists(command)) {
        return Decision.selected();
      }
    }
    if (commands.size() == 1) {
      return missingCommand(label, commands.getFirst());
    }
    return Decision.skipped("when." + label + " expected one of " + commands + " on PATH");
  }

  private Decision oneOf(List<WhenDocument> branches, HostFacts facts) {
    if (branches.isEmpty()) {
      return Decision.selected();
    }
    for (WhenDocument branch : branches) {
      if (branch != null && evaluate(branch, facts).matches()) {
        return Decision.selected();
      }
    }
    return Decision.skipped("when.oneOf no branch matched");
  }

  private List<String> matcherValues(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }
    if (node.isTextual()) {
      return List.of(node.asText().strip());
    }
    if (node.isArray()) {
      return arrayValues(node);
    }
    if (node.isObject()) {
      return objectMatcherValues(node);
    }
    return List.of();
  }

  private List<String> objectMatcherValues(JsonNode node) {
    return firstPresent(node, "oneOf")
        .or(() -> firstPresent(node, "equals"))
        .or(() -> firstPresent(node, "value"))
        .map(this::matcherValues)
        .orElseGet(List::of);
  }

  private Optional<JsonNode> firstPresent(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null ? Optional.empty() : Optional.of(value);
  }

  private List<String> arrayValues(JsonNode node) {
    var values = new ArrayList<String>();
    node.forEach(value -> {
      if (value.isTextual() && !value.asText().isBlank()) {
        values.add(value.asText().strip());
      }
    });
    return List.copyOf(values);
  }

  private boolean matchesAny(List<String> expected, Optional<String> actual) {
    return actual.map(value -> expected.stream().anyMatch(match -> matches(match, value)))
        .orElse(false);
  }

  private boolean matches(String expected, String actual) {
    return normalize(expected).equals(normalize(actual));
  }

  private String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  private String reason(String label, List<String> expected, Optional<String> actual) {
    return "when."
        + label
        + " expected one of "
        + expected
        + " but was "
        + actual.orElse("<unknown>");
  }

  private Decision missingCommand(String label, String command) {
    return Decision.skipped("when." + label + " expected '" + command + "' on PATH");
  }

  private String planName(PlanEntryDocument entry) {
    return entry.name().orElse("<unnamed>");
  }

  private String planKind(PlanEntryDocument entry) {
    return entry.kind().orElse("<unknown>");
  }

  record PlanSelection(List<PlanEntryDocument> selected, List<SkippedPlanEntry> skipped) {}

  record SkippedPlanEntry(String name, String kind, String reason) {}

  private record Decision(boolean matches, String reason) {
    static Decision selected() {
      return new Decision(true, "");
    }

    static Decision skipped(String reason) {
      return new Decision(false, reason);
    }
  }

  private enum CommandMode {
    ALL,
    ANY
  }
}
