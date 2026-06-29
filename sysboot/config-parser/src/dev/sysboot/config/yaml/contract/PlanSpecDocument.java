package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PlanSpecDocument {

  @JsonProperty("packages")
  private List<String> packages;

  @JsonProperty("actions")
  private List<PackageActionDocument> actions;

  @JsonProperty("apps")
  private List<String> apps;

  @JsonProperty("appIds")
  private List<String> appIds;

  @JsonProperty("remote")
  private String remote;

  @JsonProperty("source")
  private SourceSpecDocument source;

  @JsonProperty("sources")
  private List<SourceDocument> sources;

  @JsonProperty("checksum")
  private WorkstationChecksumDocument checksum;

  @JsonProperty("checksumUrl")
  private String checksumUrl;

  @JsonProperty("signatureUrl")
  private String signatureUrl;

  @JsonProperty("binaryName")
  private String binaryName;

  @JsonProperty("url")
  private String url;

  @JsonProperty("installPath")
  private String installPath;

  @JsonProperty("archivePath")
  private String archivePath;

  @JsonProperty("stripComponents")
  private Integer stripComponents;

  @JsonProperty("mode")
  private String mode;

  @JsonProperty("installMode")
  private String installMode;

  @JsonProperty("symlink")
  private String symlink;

  @JsonProperty("symlinkPath")
  private String symlinkPath;

  @JsonProperty("destination")
  private String destination;

  @JsonProperty("config")
  private JsonNode config;

  @JsonProperty("configPath")
  private String configPath;

  @JsonProperty("script")
  private String script;

  @JsonProperty("scripts")
  private JsonNode scripts;

  @JsonProperty("commands")
  private JsonNode commands;

  @JsonProperty("args")
  private List<String> args;

  @JsonProperty("shell")
  private String shell;

  @JsonProperty("cwd")
  private String cwd;

  @JsonProperty("workingDir")
  private String workingDir;

  @JsonProperty("env")
  private JsonNode env;

  @JsonProperty("sudo")
  private Boolean sudo;

  @JsonProperty("allowedExitCodes")
  private List<Integer> allowedExitCodes;

  @JsonProperty("creates")
  private String creates;

  @JsonProperty("unless")
  private String unless;

  @JsonProperty("confirm")
  private String confirm;

  @JsonProperty("message")
  private String message;

  @JsonProperty("instructions")
  private List<String> instructions;

  @JsonProperty("resumeFrom")
  private String resumeFrom;

  @JsonProperty("exitCode")
  private Integer exitCode;

  @JsonProperty("timeout")
  private String timeout;

  @JsonProperty("timeoutSeconds")
  private Integer timeoutSeconds;

  @JsonProperty("installerVersion")
  private String installerVersion;

  @JsonProperty("dotbotBinary")
  private String dotbotBinary;

  @JsonProperty("nerdfontBinary")
  private String nerdfontBinary;

  @JsonProperty("probeCommand")
  private String probeCommand;

  @JsonProperty("versionCommand")
  private String versionCommand;

  @JsonProperty("expectedVersion")
  private String expectedVersion;

  @JsonProperty("release")
  private String release;

  @JsonProperty("refreshFontCache")
  private Boolean refreshFontCache;

  @JsonProperty("families")
  private List<String> families;

  public List<String> packages() {
    return DocumentDefaults.list(packages);
  }

  public List<PackageActionDocument> actions() {
    return DocumentDefaults.list(actions);
  }

  public List<String> apps() {
    return DocumentDefaults.list(apps);
  }

  public List<String> appIds() {
    return DocumentDefaults.list(appIds);
  }

  public Optional<String> remote() {
    return DocumentDefaults.optional(remote);
  }

  public Optional<SourceSpecDocument> source() {
    return DocumentDefaults.optional(source);
  }

  public List<SourceDocument> sources() {
    return DocumentDefaults.list(sources);
  }

  public Optional<WorkstationChecksumDocument> checksum() {
    return DocumentDefaults.optional(checksum);
  }

  public Optional<String> checksumUrl() {
    return DocumentDefaults.optional(checksumUrl);
  }

  public Optional<String> signatureUrl() {
    return DocumentDefaults.optional(signatureUrl);
  }

  public Optional<String> binaryName() {
    return DocumentDefaults.optional(binaryName);
  }

  public Optional<String> url() {
    return DocumentDefaults.optional(url);
  }

  public Optional<String> installPath() {
    return DocumentDefaults.optional(installPath);
  }

  public Optional<String> archivePath() {
    return DocumentDefaults.optional(archivePath);
  }

  public Optional<Integer> stripComponents() {
    return DocumentDefaults.optional(stripComponents);
  }

  public Optional<String> installMode() {
    return DocumentDefaults.optional(installMode).or(() -> DocumentDefaults.optional(mode));
  }

  public Optional<String> symlinkPath() {
    return DocumentDefaults.optional(symlinkPath).or(() -> DocumentDefaults.optional(symlink));
  }

  public Optional<String> destination() {
    return DocumentDefaults.optional(destination);
  }

  public Optional<String> config() {
    if (config == null || config.isNull() || !config.isTextual()) {
      return Optional.empty();
    }
    return DocumentDefaults.optional(config.asText());
  }

  public Optional<String> configPath() {
    return DocumentDefaults.optional(configPath);
  }

  public Optional<String> dotfilesConfig() {
    return config().or(this::configPath);
  }

  public Optional<NerdFontConfigDocument> nerdFontConfig() {
    if (config == null || config.isNull() || !config.isObject()) {
      return Optional.empty();
    }
    var document = new NerdFontConfigDocument();
    document.release = textField(config, "release").orElse("latest");
    document.destination = textField(config, "destination").orElse(null);
    document.refreshFontCache = booleanField(config, "refreshFontCache").orElse(true);
    document.families = stringList(config.get("families"));
    return Optional.of(document);
  }

  public boolean configIsObject() {
    return config != null && config.isObject();
  }

  public boolean configIsText() {
    return config != null && config.isTextual();
  }

  public Optional<String> script() {
    return DocumentDefaults.optional(script);
  }

  public List<String> commands() {
    return stringList(commands);
  }

  public List<JsonNode> commandItems() {
    return nodeItems(commands);
  }

  public List<JsonNode> scriptItems() {
    return nodeItems(scripts);
  }

  public Optional<JsonNode> commandsNode() {
    return DocumentDefaults.optional(commands);
  }

  public Optional<JsonNode> envNode() {
    return DocumentDefaults.optional(env);
  }

  public List<String> args() {
    return DocumentDefaults.list(args);
  }

  public Optional<String> shell() {
    return DocumentDefaults.optional(shell);
  }

  public Optional<String> workingDir() {
    return DocumentDefaults.optional(workingDir).or(() -> DocumentDefaults.optional(cwd));
  }

  public Optional<Boolean> sudo() {
    return DocumentDefaults.optional(sudo);
  }

  public List<Integer> allowedExitCodes() {
    return DocumentDefaults.list(allowedExitCodes);
  }

  public Optional<String> creates() {
    return DocumentDefaults.optional(creates);
  }

  public Optional<String> unless() {
    return DocumentDefaults.optional(unless);
  }

  public Optional<String> confirm() {
    return DocumentDefaults.optional(confirm);
  }

  public Optional<String> message() {
    return DocumentDefaults.optional(message);
  }

  public List<String> instructions() {
    return DocumentDefaults.list(instructions);
  }

  public Optional<String> resumeFrom() {
    return DocumentDefaults.optional(resumeFrom);
  }

  public Optional<Integer> exitCode() {
    return DocumentDefaults.optional(exitCode);
  }

  public Optional<String> timeout() {
    return DocumentDefaults.optional(timeout);
  }

  public Optional<Integer> timeoutSeconds() {
    return DocumentDefaults.optional(timeoutSeconds);
  }

  public Optional<String> installerVersion() {
    return DocumentDefaults.optional(installerVersion);
  }

  public Optional<String> dotbotBinary() {
    return DocumentDefaults.optional(dotbotBinary);
  }

  public Optional<String> nerdfontBinary() {
    return DocumentDefaults.optional(nerdfontBinary);
  }

  public Optional<String> probeCommand() {
    return DocumentDefaults.optional(probeCommand);
  }

  public Optional<String> versionCommand() {
    return DocumentDefaults.optional(versionCommand);
  }

  public Optional<String> expectedVersion() {
    return DocumentDefaults.optional(expectedVersion);
  }

  public Optional<String> release() {
    return DocumentDefaults.optional(release);
  }

  public Optional<Boolean> refreshFontCache() {
    return DocumentDefaults.optional(refreshFontCache);
  }

  public List<String> families() {
    return DocumentDefaults.list(families);
  }

  private Optional<String> textField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
  }

  private Optional<Boolean> booleanField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isBoolean() ? Optional.of(value.asBoolean()) : Optional.empty();
  }

  private List<String> stringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    var values = new ArrayList<String>();
    node.forEach(value -> values.add(value.isTextual() ? value.asText() : ""));
    return List.copyOf(values);
  }

  private List<JsonNode> nodeItems(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }
    if (!node.isArray()) {
      return List.of(node);
    }
    var values = new ArrayList<JsonNode>();
    node.forEach(values::add);
    return List.copyOf(values);
  }

  public List<String> envNames() {
    if (env == null || !env.isObject()) {
      return List.of();
    }
    var names = new ArrayList<String>();
    Iterator<Map.Entry<String, JsonNode>> fields = env.fields();
    while (fields.hasNext()) {
      names.add(fields.next().getKey());
    }
    return List.copyOf(names);
  }
}
