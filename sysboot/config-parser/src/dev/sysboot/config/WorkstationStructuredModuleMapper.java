package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.WhenDocument;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ScriptPath;
import dev.sysboot.core.SecretRedactor;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellEnvironmentVariable;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkstationStructuredModuleMapper {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final WorkstationProfileWhenEvaluator whenEvaluator;
  private final WorkstationMappingSupport support;
  private final Path manifestDirectory;

  WorkstationStructuredModuleMapper(
      WorkstationProfileWhenEvaluator whenEvaluator,
      WorkstationMappingSupport support,
      Path manifestDirectory) {
    this.whenEvaluator = whenEvaluator;
    this.support = support;
    this.manifestDirectory = manifestDirectory.toAbsolutePath().normalize();
  }

  ShellScriptModule shellScriptModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new ShellScriptModule(
        new ModuleName(support.planName(entry)),
        scriptItems(entry, spec),
        Optional.of(spec.workingDir().map(this::localPath).orElse(manifestDirectory)),
        support.continueOnError(entry, policy),
        spec.probeCommand());
  }

  ShellCommandModule shellCommandModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new ShellCommandModule(
        new ModuleName(support.planName(entry)),
        commandItems(entry, spec),
        spec.shell().orElse("/bin/bash"),
        Optional.of(spec.workingDir().map(this::localPath).orElse(manifestDirectory)),
        support.continueOnError(entry, policy),
        spec.probeCommand());
  }

  Optional<dev.sysboot.core.BootstrapModule> fileWriteModule(
      PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    List<FileWriteItem> items = fileWriteItems(entry, spec);
    if (items.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new FileWriteModule(
            new ModuleName(support.planName(entry)),
            items,
            support.continueOnError(entry, policy)));
  }

  private List<FileWriteItem> fileWriteItems(PlanEntryDocument entry, PlanSpecDocument spec) {
    List<JsonNode> nodes = spec.fileWriteItems();
    if (nodes.isEmpty()) {
      return List.of(fileWriteItem(entry, spec, null, 0));
    }
    var items = new ArrayList<FileWriteItem>();
    for (int index = 0; index < nodes.size(); index++) {
      if (itemMatches(nodes.get(index))) {
        items.add(fileWriteItem(entry, spec, nodes.get(index), index));
      }
    }
    return List.copyOf(items);
  }

  private FileWriteItem fileWriteItem(
      PlanEntryDocument entry, PlanSpecDocument spec, JsonNode node, int index) {
    String name = text(node, "name").orElse(support.planName(entry) + "[" + index + "]");
    return new FileWriteItem(
        name,
        path(node, "destination")
            .or(() -> spec.destination().map(support::absolutePath))
            .orElseThrow(),
        child(node, "content").filter(JsonNode::isTextual).map(JsonNode::asText).or(spec::content),
        path(node, "source").or(() -> spec.fileSource().map(support::absolutePath)),
        text(node, "owner").or(spec::owner),
        text(node, "group").or(spec::group),
        text(node, "mode").or(spec::installMode),
        bool(node, "sudo").or(spec::sudo).orElse(false));
  }

  private List<ShellScriptItem> scriptItems(PlanEntryDocument entry, PlanSpecDocument spec) {
    List<JsonNode> nodes = spec.scriptItems();
    if (nodes.isEmpty()) {
      return List.of(scriptItem(entry, spec, null, 0));
    }
    var items = new ArrayList<ShellScriptItem>();
    for (int index = 0; index < nodes.size(); index++) {
      if (itemMatches(nodes.get(index))) {
        items.add(scriptItem(entry, spec, nodes.get(index), index));
      }
    }
    return List.copyOf(items);
  }

  private ShellScriptItem scriptItem(
      PlanEntryDocument entry, PlanSpecDocument spec, JsonNode node, int index) {
    String name = text(node, "name").orElse(support.planName(entry) + "[" + index + "]");
    return new ShellScriptItem(
        name,
        text(node, "script").or(spec::script).map(raw -> new ScriptPath(localPath(raw))),
        text(node, "url").or(spec::url).map(URI::create),
        stringList(node, "args").orElseGet(spec::args),
        localPath(node, "cwd")
            .or(() -> localPath(node, "workingDir"))
            .or(() -> spec.workingDir().map(this::localPath))
            .or(() -> Optional.of(manifestDirectory)),
        environment(spec.envNode(), child(node, "env")),
        bool(node, "sudo").or(spec::sudo).orElse(false),
        intList(node, "allowedExitCodes").orElseGet(spec::allowedExitCodes),
        localPath(node, "creates").or(() -> spec.creates().map(this::localPath)),
        text(node, "unless").or(spec::unless),
        confirm(node).or(spec::confirm),
        timeout(node).orElseGet(() -> timeout(spec)),
        text(node, "sha256").or(spec::sha256).map(Sha256Digest::new));
  }

  private List<ShellCommandItem> commandItems(PlanEntryDocument entry, PlanSpecDocument spec) {
    List<JsonNode> nodes = spec.commandItems();
    var items = new ArrayList<ShellCommandItem>();
    for (int index = 0; index < nodes.size(); index++) {
      if (itemMatches(nodes.get(index))) {
        items.add(commandItem(entry, spec, nodes.get(index), index));
      }
    }
    return List.copyOf(items);
  }

  private ShellCommandItem commandItem(
      PlanEntryDocument entry, PlanSpecDocument spec, JsonNode node, int index) {
    String fallback =
        node.isTextual() ? node.asText() : support.planName(entry) + "[" + index + "]";
    return new ShellCommandItem(
        text(node, "name").orElse(fallback),
        node.isTextual()
            ? Optional.of(node.asText())
            : text(node, "run").or(() -> text(node, "shellCommand")),
        argv(node),
        text(node, "shell").or(spec::shell).orElse("/bin/bash"),
        localPath(node, "cwd")
            .or(() -> localPath(node, "workingDir"))
            .or(() -> spec.workingDir().map(this::localPath))
            .or(() -> Optional.of(manifestDirectory)),
        environment(spec.envNode(), child(node, "env")),
        bool(node, "sudo").or(spec::sudo).orElse(false),
        intList(node, "allowedExitCodes").orElseGet(spec::allowedExitCodes),
        localPath(node, "creates").or(() -> spec.creates().map(this::localPath)),
        text(node, "unless").or(spec::unless),
        confirm(node).or(spec::confirm),
        timeout(node).orElseGet(() -> timeout(spec)));
  }

  private Optional<List<String>> argv(JsonNode node) {
    if (node.isArray()) {
      return Optional.of(stringArray(node));
    }
    return array(node, "run").or(() -> array(node, "argv")).or(() -> commandWithArgs(node));
  }

  private Optional<List<String>> commandWithArgs(JsonNode node) {
    return text(node, "command")
        .map(
            command -> {
              var values = new ArrayList<String>();
              values.add(command);
              values.addAll(stringList(node, "args").orElse(List.of()));
              return List.copyOf(values);
            });
  }

  private List<ShellEnvironmentVariable> environment(
      Optional<JsonNode> moduleEnv, Optional<JsonNode> itemEnv) {
    var values = new LinkedHashMap<String, ShellEnvironmentVariable>();
    moduleEnv.ifPresent(env -> addEnvironment(values, env));
    itemEnv.ifPresent(env -> addEnvironment(values, env));
    return List.copyOf(values.values());
  }

  private void addEnvironment(Map<String, ShellEnvironmentVariable> values, JsonNode env) {
    if (env == null || !env.isObject()) {
      throw new IllegalArgumentException("env must be an object");
    }
    for (Map.Entry<String, JsonNode> field : env.properties()) {
      values.put(field.getKey(), environmentVariable(field.getKey(), field.getValue()));
    }
  }

  private ShellEnvironmentVariable environmentVariable(String name, JsonNode value) {
    if (value.isTextual()) {
      return new ShellEnvironmentVariable(
          name, value.asText(), SecretRedactor.isSensitiveName(name));
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("env." + name + " must be a string or object");
    }
    JsonNode raw = value.get("value");
    if (raw == null || !raw.isTextual()) {
      throw new IllegalArgumentException("env." + name + ".value must be a string");
    }
    boolean sensitive =
        value.has("sensitive")
            ? value.get("sensitive").asBoolean()
            : SecretRedactor.isSensitiveName(name);
    return new ShellEnvironmentVariable(name, raw.asText(), sensitive);
  }

  private boolean itemMatches(JsonNode node) {
    return child(node, "when")
        .map(
            value ->
                whenEvaluator.matches(Optional.of(MAPPER.convertValue(value, WhenDocument.class))))
        .orElse(true);
  }

  private Optional<JsonNode> child(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return Optional.empty();
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
  }

  private Optional<String> text(JsonNode node, String field) {
    return child(node, field)
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .filter(value -> !value.isBlank());
  }

  private Optional<Boolean> bool(JsonNode node, String field) {
    return child(node, field).filter(JsonNode::isBoolean).map(JsonNode::asBoolean);
  }

  private Optional<Path> path(JsonNode node, String field) {
    return text(node, field).map(value -> Path.of(MappingSupport.expandHome(value)));
  }

  private Optional<Path> localPath(JsonNode node, String field) {
    return text(node, field).map(this::localPath);
  }

  private Path localPath(String raw) {
    Path path = Path.of(MappingSupport.expandHome(raw));
    return path.isAbsolute() ? path.normalize() : manifestDirectory.resolve(path).normalize();
  }

  private Optional<List<Integer>> intList(JsonNode node, String field) {
    return child(node, field)
        .filter(JsonNode::isArray)
        .map(
            values -> {
              var result = new ArrayList<Integer>();
              values.forEach(
                  value -> {
                    if (value.canConvertToInt()) {
                      result.add(value.asInt());
                    }
                  });
              return List.copyOf(result);
            });
  }

  private Optional<List<String>> stringList(JsonNode node, String field) {
    return child(node, field).filter(JsonNode::isArray).map(this::stringArray);
  }

  private Optional<List<String>> array(JsonNode node, String field) {
    return stringList(node, field).filter(values -> !values.isEmpty());
  }

  private List<String> stringArray(JsonNode node) {
    var values = new ArrayList<String>();
    node.forEach(
        value -> {
          if (value.isTextual() && !value.asText().isBlank()) {
            values.add(value.asText());
          }
        });
    return List.copyOf(values);
  }

  private Optional<String> confirm(JsonNode node) {
    return text(node, "confirm")
        .or(() -> bool(node, "confirm").filter(Boolean::booleanValue).map(ignored -> "confirm"));
  }

  private Optional<Duration> timeout(JsonNode node) {
    return text(node, "timeout")
        .map(MappingSupport::duration)
        .or(
            () ->
                child(node, "timeoutSeconds")
                    .filter(JsonNode::canConvertToInt)
                    .map(value -> Duration.ofSeconds(value.asInt())));
  }

  private Duration timeout(PlanSpecDocument spec) {
    return spec.timeout()
        .map(MappingSupport::duration)
        .or(() -> spec.timeoutSeconds().map(Duration::ofSeconds))
        .orElse(Duration.ofMinutes(30));
  }

  private PlanSpecDocument spec(PlanEntryDocument entry) {
    return MappingSupport.requireField(
        entry.spec().orElse(null), support.planName(entry) + ".spec");
  }
}
