package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.config.ConfigLoadException;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ZypperModule;
import dev.sysboot.executor.CompiledBinaryArtifactFormat;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.StateReadException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "doctor", description = "Check host readiness for a Fluxion profile")
public final class DoctorCommand implements Runnable {

  private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(3);

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = {"--profile"},
      description = "Profile name for state checks",
      defaultValue = "default")
  private String profile;

  @Option(
      names = {"--skip-network"},
      description = "Skip network checks for compiled binary URLs")
  private boolean skipNetwork;

  @Override
  public void run() {
    List<Check> checks = runChecks();
    checks.forEach(check -> spec.commandLine().getOut().println(check.render()));
    long failures = checks.stream().filter(Check::failed).count();
    if (failures > 0) {
      throw new CliFailureException(
          ExitCode.EXTERNAL_DEPENDENCY_ERROR, "Doctor found " + failures + " failing check(s)");
    }
  }

  private List<Check> runChecks() {
    var checks = new ArrayList<Check>();
    Path configFile = options.resolvedConfigFile();
    Optional<BootstrapConfig> config = loadConfig(configFile, checks);
    checks.add(checkHostOs());
    checks.add(checkStateDirectory());
    checks.add(checkCommand("sudo", "sudo command"));
    config.ifPresent(value -> addConfigChecks(value, checks));
    return List.copyOf(checks);
  }

  private Optional<BootstrapConfig> loadConfig(Path configFile, List<Check> checks) {
    if (!Files.isReadable(configFile)) {
      checks.add(Check.fail("config file", "not readable: " + configFile));
      return Optional.empty();
    }
    try {
      BootstrapConfig config =
          ApplicationContext.create(true).configLoader().load(configFile.toAbsolutePath());
      checks.add(Check.pass("config file", "loaded " + config.profileName().value()));
      return Optional.of(config);
    } catch (ConfigLoadException e) {
      checks.add(Check.fail("config file", e.getMessage()));
      return Optional.empty();
    }
  }

  private Check checkHostOs() {
    Path osRelease = Path.of("/etc/os-release");
    if (!Files.exists(osRelease)) {
      return Check.warn("host os", "/etc/os-release not found");
    }
    try {
      String id = osReleaseValue(Files.readString(osRelease), "ID");
      return supportedOs(id)
          ? Check.pass("host os", id)
          : Check.warn("host os", "unsupported or untested: " + id);
    } catch (IOException e) {
      return Check.warn("host os", "cannot read /etc/os-release");
    }
  }

  private boolean supportedOs(String id) {
    return Set.of(
            "fedora",
            "arch",
            "archlinux",
            "opensuse-tumbleweed",
            "opensuse-leap",
            "debian",
            "ubuntu")
        .contains(id.toLowerCase(Locale.ROOT));
  }

  private String osReleaseValue(String content, String key) {
    return content
        .lines()
        .filter(line -> line.startsWith(key + "="))
        .map(line -> line.substring(key.length() + 1).replace("\"", ""))
        .findFirst()
        .orElse("unknown");
  }

  private Check checkStateDirectory() {
    try {
      Path stateFile = new JsonStateRepository(new ObjectMapper()).path(profile);
      Files.createDirectories(stateFile.getParent());
      Path probe = Files.createTempFile(stateFile.getParent(), ".doctor", ".tmp");
      Files.deleteIfExists(probe);
      return Check.pass("state directory", "writable: " + stateFile.getParent());
    } catch (IOException | StateReadException e) {
      return Check.fail("state directory", e.getMessage());
    }
  }

  private Check checkCommand(String command, String label) {
    return commandExists(command)
        ? Check.pass(label, command)
        : Check.warn(label, "not found on PATH");
  }

  private boolean commandExists(String command) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    return java.util.Arrays.stream(path.split(java.io.File.pathSeparator))
        .map(Path::of)
        .map(dir -> dir.resolve(command))
        .anyMatch(Files::isExecutable);
  }

  private void addConfigChecks(BootstrapConfig config, List<Check> checks) {
    checks.add(Check.pass("target os", config.target().getClass().getSimpleName()));
    requiredPackageManagers(config).stream()
        .map(this::packageManagerCommand)
        .forEach(command -> checks.add(checkRequiredCommand(command, "package manager")));
    config.modules().stream().forEach(module -> addModuleChecks(module, checks));
  }

  private Set<PackageManagerKind> requiredPackageManagers(BootstrapConfig config) {
    EnumSet<PackageManagerKind> managers = EnumSet.noneOf(PackageManagerKind.class);
    for (BootstrapModule module : config.modules()) {
      switch (module) {
        case PackageModule pm -> managers.add(pm.packageManager());
        case ZypperModule ignored -> managers.add(PackageManagerKind.ZYPPER);
        default -> {}
      }
    }
    return managers;
  }

  private String packageManagerCommand(PackageManagerKind kind) {
    return switch (kind) {
      case DNF -> "dnf";
      case PACMAN -> "pacman";
      case PARU -> "paru";
      case YAY -> "yay";
      case APT -> "apt-get";
      case FLATPAK -> "flatpak";
      case ZYPPER -> "zypper";
    };
  }

  private Check checkRequiredCommand(String command, String label) {
    return commandExists(command)
        ? Check.pass(label, command)
        : Check.fail(label, command + " not found on PATH");
  }

  private void addModuleChecks(BootstrapModule module, List<Check> checks) {
    switch (module) {
      case FlatpakModule fm -> addFlatpakChecks(fm, checks);
      case DefaultShellModule dsm -> checks.add(checkShellPath(dsm.shellPath()));
      case ShellCommandModule scm -> checks.add(checkRequiredCommand(scm.shell(), "shell"));
      case AssertModule am -> checks.add(checkRequiredCommand(am.shell(), "assert shell"));
      case CompiledBinaryModule cbm -> addNetworkCheck(cbm, checks);
      default -> {}
    }
  }

  private void addFlatpakChecks(FlatpakModule module, List<Check> checks) {
    checks.add(checkRequiredCommand("flatpak", "flatpak command"));
    if (module.remote().equals("flathub")) {
      checks.add(Check.warn("flatpak remote", "verify Flathub is configured before run"));
    }
  }

  private Check checkShellPath(Path shellPath) {
    return Files.isExecutable(shellPath)
        ? Check.pass("shell path", shellPath.toString())
        : Check.fail("shell path", shellPath + " is not executable");
  }

  private void addNetworkCheck(CompiledBinaryModule module, List<Check> checks) {
    if (!CompiledBinaryArtifactFormat.isSupported(module.url().value())) {
      checks.add(
          Check.fail(
              "binary artifact",
              module.url().value() + " is not " + CompiledBinaryArtifactFormat.supportedFormats()));
      return;
    }
    if (skipNetwork) {
      checks.add(Check.warn("network", "skipped " + module.url().value()));
      module
          .checksumUrl()
          .ifPresent(url -> checks.add(Check.warn("checksum network", "skipped " + url.value())));
      return;
    }
    checks.add(checkUrl("network", module.url().value()));
    module.checksumUrl().ifPresent(url -> checks.add(checkUrl("checksum network", url.value())));
  }

  private Check checkUrl(String label, URI uri) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .timeout(NETWORK_TIMEOUT)
              .build();
      int status =
          HttpClient.newHttpClient()
              .send(request, HttpResponse.BodyHandlers.discarding())
              .statusCode();
      return status < 400
          ? Check.pass(label, uri.toString())
          : Check.fail(label, uri + " -> " + status);
    } catch (IOException e) {
      return Check.fail(label, uri + " -> " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Check.fail(label, uri + " -> interrupted");
    }
  }

  private record Check(CheckStatus status, String name, String detail) {
    boolean failed() {
      return status == CheckStatus.FAIL;
    }

    String render() {
      return "[%s] %-18s %s".formatted(status.label(), name, detail);
    }

    static Check pass(String name, String detail) {
      return new Check(CheckStatus.PASS, name, detail);
    }

    static Check warn(String name, String detail) {
      return new Check(CheckStatus.WARN, name, detail);
    }

    static Check fail(String name, String detail) {
      return new Check(CheckStatus.FAIL, name, detail);
    }
  }

  private enum CheckStatus {
    PASS,
    WARN,
    FAIL;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
