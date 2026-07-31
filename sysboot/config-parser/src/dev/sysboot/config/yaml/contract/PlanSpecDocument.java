package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PlanSpecDocument {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonProperty("packages")
  private JsonNode packages;

  @JsonProperty("packageManager")
  private String packageManager;

  @JsonProperty("actions")
  private List<PackageActionDocument> actions;

  @JsonProperty("apps")
  private List<String> apps;

  @JsonProperty("appIds")
  private List<String> appIds;

  @JsonProperty("remote")
  private String remote;

  @JsonProperty("source")
  private JsonNode source;

  @JsonProperty("sources")
  private List<SourceDocument> sources;

  @JsonProperty("files")
  private JsonNode files;

  @JsonProperty("writes")
  private JsonNode writes;

  @JsonProperty("content")
  private JsonNode content;

  @JsonProperty("owner")
  private String owner;

  @JsonProperty("group")
  private String group;

  @JsonProperty("checksum")
  private WorkstationChecksumDocument checksum;

  @JsonProperty("checksumUrl")
  private String checksumUrl;

  @JsonProperty("signatureUrl")
  private String signatureUrl;

  @JsonProperty("allowedSignerFingerprint")
  private String allowedSignerFingerprint;

  @JsonProperty("binaryName")
  private String binaryName;

  @JsonProperty("url")
  private String url;

  @JsonProperty("sha256")
  private String sha256;

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

  @JsonProperty("binstallerBinary")
  private String binstallerBinary;

  @JsonProperty("groups")
  private List<String> groups;

  @JsonProperty("scope")
  private String scope;

  @JsonProperty("id")
  private String repositoryId;

  @JsonProperty("baseUrl")
  private String baseUrl;

  @JsonProperty("repoFile")
  private String repoFile;

  @JsonProperty("gpgKeyUrl")
  private String gpgKeyUrl;

  @JsonProperty("gpgCheck")
  private Boolean gpgCheck;

  @JsonProperty("autoRefresh")
  private Boolean autoRefresh;

  @JsonProperty("enabled")
  private Boolean repoEnabled;

  @JsonProperty("entries")
  private java.util.Map<String, String> entries;

  @JsonProperty("repos")
  private List<GitRepoModuleDocument.RepoDocument> repos;

  @JsonProperty("units")
  private List<SystemdUnitModuleDocument.UnitDocument> units;

  @JsonProperty("keys")
  private List<GpgKeyModuleDocument.KeyDocument> keys;

  @JsonProperty("backend")
  private String backend;

  @JsonProperty("localRtc")
  private Boolean localRtc;

  @JsonProperty("ntp")
  private Boolean ntp;

  @JsonProperty("timezone")
  private String timezone;

  @JsonProperty("hostname")
  private String hostname;

  @JsonProperty("locale")
  private java.util.Map<String, String> locale;

  @JsonProperty("distUpgrade")
  private Boolean distUpgrade;

  @JsonProperty("refreshOnly")
  private Boolean refreshOnly;

  @JsonProperty("user")
  private String user;

  @JsonProperty("createMissing")
  private Boolean createMissing;

  @JsonProperty("logoutCheckpoint")
  private Boolean logoutCheckpoint;

  @JsonProperty("only")
  private List<String> only;

  @JsonProperty("skip")
  private List<String> skip;

  @JsonProperty("locked")
  private Boolean locked;

  @JsonProperty("lockFile")
  private String lockFile;

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
    return stringList(packages);
  }

  public List<JsonNode> packageItems() {
    return nodeItems(packages);
  }

  public Optional<String> packageManager() {
    return DocumentDefaults.optional(packageManager);
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
    if (source == null || source.isNull() || !source.isObject()) {
      return Optional.empty();
    }
    return Optional.of(MAPPER.convertValue(source, SourceSpecDocument.class));
  }

  public Optional<String> fileSource() {
    if (source == null || source.isNull() || !source.isTextual()) {
      return Optional.empty();
    }
    return DocumentDefaults.optional(source.asText());
  }

  public List<SourceDocument> sources() {
    return DocumentDefaults.list(sources);
  }

  public List<JsonNode> fileWriteItems() {
    List<JsonNode> fileItems = nodeItems(files);
    return fileItems.isEmpty() ? nodeItems(writes) : fileItems;
  }

  public Optional<String> content() {
    if (content == null || content.isNull() || !content.isTextual()) {
      return Optional.empty();
    }
    return Optional.of(content.asText());
  }

  public Optional<JsonNode> contentNode() {
    return DocumentDefaults.optional(content);
  }

  public Optional<String> owner() {
    return DocumentDefaults.optional(owner);
  }

  public Optional<String> group() {
    return DocumentDefaults.optional(group);
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

  public Optional<String> allowedSignerFingerprint() {
    return DocumentDefaults.optional(allowedSignerFingerprint);
  }

  public Optional<String> binaryName() {
    return DocumentDefaults.optional(binaryName);
  }

  public Optional<String> url() {
    return DocumentDefaults.optional(url);
  }

  public Optional<String> sha256() {
    return DocumentDefaults.optional(sha256);
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

  /**
   * Path to an existing Nerd Fonts installer config. A textual {@code config} names a file; an
   * object {@code config} is an inline definition handled by {@link #nerdFontConfig()}.
   */
  public Optional<String> nerdFontsConfigPath() {
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

  public Optional<String> binstallerBinary() {
    return DocumentDefaults.optional(binstallerBinary);
  }

  public List<String> groups() {
    return DocumentDefaults.list(groups);
  }

  public Optional<String> scope() {
    return DocumentDefaults.optional(scope);
  }

  public Optional<String> repositoryId() {
    return DocumentDefaults.optional(repositoryId);
  }

  public Optional<String> baseUrl() {
    return DocumentDefaults.optional(baseUrl);
  }

  public Optional<String> repoFile() {
    return DocumentDefaults.optional(repoFile);
  }

  public Optional<String> gpgKeyUrl() {
    return DocumentDefaults.optional(gpgKeyUrl);
  }

  public boolean gpgCheck() {
    return DocumentDefaults.optional(gpgCheck).orElse(true);
  }

  public boolean autoRefresh() {
    return DocumentDefaults.optional(autoRefresh).orElse(true);
  }

  public boolean repoEnabled() {
    return DocumentDefaults.optional(repoEnabled).orElse(true);
  }

  public java.util.Map<String, String> entries() {
    return entries == null ? java.util.Map.of() : java.util.Map.copyOf(entries);
  }

  public List<GitRepoModuleDocument.RepoDocument> repos() {
    return DocumentDefaults.list(repos);
  }

  public List<SystemdUnitModuleDocument.UnitDocument> units() {
    return DocumentDefaults.list(units);
  }

  public List<GpgKeyModuleDocument.KeyDocument> keys() {
    return DocumentDefaults.list(keys);
  }

  public Optional<String> backend() {
    return DocumentDefaults.optional(backend);
  }

  public Optional<Boolean> localRtc() {
    return DocumentDefaults.optional(localRtc);
  }

  public Optional<Boolean> ntp() {
    return DocumentDefaults.optional(ntp);
  }

  public Optional<String> timezone() {
    return DocumentDefaults.optional(timezone);
  }

  public Optional<String> hostname() {
    return DocumentDefaults.optional(hostname);
  }

  public java.util.Map<String, String> locale() {
    return locale == null ? java.util.Map.of() : java.util.Map.copyOf(locale);
  }

  public boolean distUpgrade() {
    return DocumentDefaults.optional(distUpgrade).orElse(false);
  }

  public boolean refreshOnly() {
    return DocumentDefaults.optional(refreshOnly).orElse(false);
  }

  public Optional<String> user() {
    return DocumentDefaults.optional(user);
  }

  public boolean createMissing() {
    return DocumentDefaults.optional(createMissing).orElse(false);
  }

  public boolean logoutCheckpoint() {
    return DocumentDefaults.optional(logoutCheckpoint).orElse(true);
  }

  public List<String> only() {
    return DocumentDefaults.list(only);
  }

  public List<String> skip() {
    return DocumentDefaults.list(skip);
  }

  public boolean locked() {
    return DocumentDefaults.optional(locked).orElse(false);
  }

  public Optional<String> lockFile() {
    return DocumentDefaults.optional(lockFile);
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
    for (Map.Entry<String, JsonNode> field : env.properties()) {
      names.add(field.getKey());
    }
    return List.copyOf(names);
  }
}
