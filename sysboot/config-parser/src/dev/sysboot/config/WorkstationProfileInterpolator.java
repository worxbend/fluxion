package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WorkstationProfileInterpolator {

  private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

  private final Map<String, String> environment;
  private final Map<String, String> hostFacts;

  WorkstationProfileInterpolator() {
    this(runtimeEnvironment(), hostFacts());
  }

  WorkstationProfileInterpolator(Map<String, String> environment, Map<String, String> hostFacts) {
    this.environment = Map.copyOf(environment);
    this.hostFacts = Map.copyOf(hostFacts);
  }

  JsonNode interpolate(JsonNode root) {
    var errors = new ArrayList<String>();
    Map<String, String> rawVars = rawSpecVars(root);
    Map<String, String> specVars = resolveSpecVars(rawVars, errors);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", errors));
    }
    JsonNode copy = root.deepCopy();
    interpolateNode(copy, "", variables(specVars), errors, Optional.empty());
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", errors));
    }
    return copy;
  }

  private Map<String, String> resolveSpecVars(
      Map<String, String> rawVars, List<String> errors) {
    var resolved = new LinkedHashMap<String, String>();
    for (String key : rawVars.keySet()) {
      resolveSpecVar(key, rawVars, resolved, new ArrayDeque<>(), errors);
    }
    return resolved;
  }

  private String resolveSpecVar(
      String key,
      Map<String, String> rawVars,
      Map<String, String> resolved,
      ArrayDeque<String> stack,
      List<String> errors) {
    if (resolved.containsKey(key)) {
      return resolved.get(key);
    }
    if (stack.contains(key)) {
      errors.add(specVarPath(key) + " contains a cyclic variable reference '${" + key + "}'");
      return rawVars.get(key);
    }
    stack.addLast(key);
    String value = resolveText(rawVars.get(key), specVarPath(key), Optional.empty(), errors,
        name -> lookupSpecVar(name, rawVars, resolved, stack, errors));
    stack.removeLast();
    resolved.put(key, value);
    return value;
  }

  private String lookupSpecVar(
      String name,
      Map<String, String> rawVars,
      Map<String, String> resolved,
      ArrayDeque<String> stack,
      List<String> errors) {
    if (environment.containsKey(name)) {
      return environment.get(name);
    }
    if (rawVars.containsKey(name)) {
      return resolveSpecVar(name, rawVars, resolved, stack, errors);
    }
    return hostFacts.get(name);
  }

  private void interpolateNode(
      JsonNode node,
      String path,
      Map<String, String> variables,
      List<String> errors,
      Optional<PlanContext> plan) {
    if (node instanceof ObjectNode object) {
      interpolateObject(object, path, variables, errors, plan);
    } else if (node instanceof ArrayNode array) {
      interpolateArray(array, path, variables, errors, plan);
    }
  }

  private void interpolateObject(
      ObjectNode object,
      String path,
      Map<String, String> variables,
      List<String> errors,
      Optional<PlanContext> plan) {
    Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String childPath = childPath(path, field.getKey());
      JsonNode child = field.getValue();
      if (child.isTextual()) {
        String resolved = resolveText(child.asText(), childPath, plan, errors, variables::get);
        object.set(field.getKey(), TextNode.valueOf(resolved));
      } else {
        interpolateNode(child, childPath, variables, errors, plan);
      }
    }
  }

  private void interpolateArray(
      ArrayNode array,
      String path,
      Map<String, String> variables,
      List<String> errors,
      Optional<PlanContext> plan) {
    for (int index = 0; index < array.size(); index++) {
      JsonNode child = array.get(index);
      String childPath = path + "[" + index + "]";
      Optional<PlanContext> childPlan = planContext(path, child, variables).or(() -> plan);
      if (child.isTextual()) {
        String resolved = resolveText(child.asText(), childPath, childPlan, errors, variables::get);
        array.set(index, TextNode.valueOf(resolved));
      } else {
        interpolateNode(child, childPath, variables, errors, childPlan);
      }
    }
  }

  private Optional<PlanContext> planContext(
      String path, JsonNode child, Map<String, String> variables) {
    if (!"spec.plan".equals(path) || !child.isObject()) {
      return Optional.empty();
    }
    JsonNode name = child.get("name");
    if (name == null || !name.isTextual()) {
      return Optional.empty();
    }
    return Optional.of(new PlanContext(resolvePlanName(name.asText(), variables)));
  }

  private String resolveText(
      String value,
      String path,
      Optional<PlanContext> plan,
      List<String> errors,
      VariableLookup lookup) {
    Matcher matcher = VARIABLE.matcher(value);
    var resolved = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1).strip();
      String replacement = name.isEmpty() ? null : lookup.value(name);
      if (replacement == null) {
        errors.add(unresolvedMessage(path, plan, matcher.group(), name));
        replacement = matcher.group();
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  private Map<String, String> variables(Map<String, String> specVars) {
    var variables = new LinkedHashMap<String, String>();
    variables.putAll(environment);
    specVars.forEach(variables::putIfAbsent);
    hostFacts.forEach(variables::putIfAbsent);
    return Map.copyOf(variables);
  }

  private Map<String, String> rawSpecVars(JsonNode root) {
    JsonNode vars = root.path("spec").path("vars");
    if (!vars.isObject()) {
      return Map.of();
    }
    var values = new LinkedHashMap<String, String>();
    vars.fields().forEachRemaining(field -> readSpecVar(values, field));
    return values;
  }

  private void readSpecVar(Map<String, String> values, Map.Entry<String, JsonNode> field) {
    if (field.getValue().isTextual()) {
      values.put(field.getKey(), field.getValue().asText());
    }
  }

  private String resolvePlanName(String value, Map<String, String> variables) {
    return resolveText(value, "spec.plan[].name", Optional.empty(), new ArrayList<>(),
        variables::get);
  }

  private String unresolvedMessage(
      String path, Optional<PlanContext> plan, String token, String name) {
    String variable = name.isEmpty() ? token : "${" + name + "}";
    return plan
        .map(context -> path + " in plan entry '" + context.name() + "' references unresolved variable " + variable)
        .orElse(path + " references unresolved variable " + variable);
  }

  private String childPath(String parent, String fieldName) {
    String child = simpleField(fieldName) ? fieldName : "['" + fieldName + "']";
    return parent.isEmpty() ? child : parent + "." + child;
  }

  private boolean simpleField(String fieldName) {
    return fieldName.matches("[A-Za-z_][A-Za-z0-9_]*");
  }

  private String specVarPath(String key) {
    return childPath("spec.vars", key);
  }

  private static Map<String, String> runtimeEnvironment() {
    var values = new LinkedHashMap<String, String>(System.getenv());
    values.putIfAbsent("HOME", System.getProperty("user.home", ""));
    values.putIfAbsent("USER", System.getProperty("user.name", ""));
    return values;
  }

  private static Map<String, String> hostFacts() {
    return Map.of(
        "host.os.name", System.getProperty("os.name", ""),
        "host.os.arch", System.getProperty("os.arch", ""),
        "host.user", System.getProperty("user.name", ""),
        "host.home", System.getProperty("user.home", ""));
  }

  @FunctionalInterface
  private interface VariableLookup {
    String value(String name);
  }

  private record PlanContext(String name) {}
}
