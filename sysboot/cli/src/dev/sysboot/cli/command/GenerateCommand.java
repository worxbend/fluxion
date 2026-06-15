package dev.sysboot.cli.command;

import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "generate", description = "Generate a starter Fluxion config")
public final class GenerateCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Option(
      names = {"--os"},
      description = "Target OS: auto, fedora, arch, opensuse, debian",
      defaultValue = "auto")
  private String os;

  @Option(
      names = {"--profile"},
      description = "Profile name",
      defaultValue = "starter")
  private String profile;

  @Option(
      names = {"--preset"},
      description = "Preset: minimal, developer, desktop, dotfiles",
      defaultValue = "minimal")
  private String preset;

  @Option(
      names = {"--output"},
      description = "Output YAML path",
      required = true)
  private Path output;

  @Option(
      names = {"--force"},
      description = "Overwrite the output file if it exists")
  private boolean force;

  @Override
  public void run() {
    Target target = resolveTarget(os);
    Preset selectedPreset = parsePreset(preset);
    writeConfig(target, selectedPreset);
  }

  private void writeConfig(Target target, Preset selectedPreset) {
    if (Files.exists(output) && !force) {
      throw new CliFailureException(
          ExitCode.INVALID_INPUT, "Output file already exists. Use --force to overwrite.");
    }
    try {
      if (output.getParent() != null) {
        Files.createDirectories(output.getParent());
      }
      Files.writeString(output, render(target, selectedPreset));
      spec.commandLine().getOut().println("Generated config: " + output.toAbsolutePath());
    } catch (IOException e) {
      throw new CliFailureException(
          ExitCode.IO_ERROR, "Failed to write config: " + output.toAbsolutePath(), e);
    }
  }

  private String render(Target target, Preset selectedPreset) {
    return """
    profile: %s
    os:
      type: %s%s

    jobs:
    %s
    """
        .formatted(profile, target.osType(), target.releaseYaml(), selectedPreset.jobs(target));
  }

  private Target resolveTarget(String requestedOs) {
    String normalized = requestedOs.toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "auto" -> detectHostTarget();
      case "fedora" -> new Target("fedora", "dnf", "");
      case "arch" -> new Target("arch", "pacman", "");
      case "opensuse" -> new Target("opensuse", "zypper", "");
      case "debian", "ubuntu" -> new Target("debian", "apt", "");
      default ->
          throw new CliFailureException(
              ExitCode.CONFIGURATION_ERROR, "Unsupported generator OS: " + requestedOs);
    };
  }

  private Target detectHostTarget() {
    Path osRelease = Path.of("/etc/os-release");
    if (!Files.exists(osRelease)) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Cannot detect OS: /etc/os-release not found");
    }
    try {
      String content = Files.readString(osRelease);
      String id = osReleaseValue(content, "ID").toLowerCase(Locale.ROOT);
      String version = osReleaseValue(content, "VERSION_ID");
      return switch (id) {
        case "fedora" -> new Target("fedora", "dnf", version);
        case "arch", "archlinux" -> new Target("arch", "pacman", "");
        case "opensuse-tumbleweed", "opensuse-leap", "sles" ->
            new Target("opensuse", "zypper", version);
        case "debian", "ubuntu" -> new Target("debian", "apt", version);
        default ->
            throw new CliFailureException(
                ExitCode.CONFIGURATION_ERROR, "Unsupported detected OS: " + id);
      };
    } catch (IOException e) {
      throw new CliFailureException(ExitCode.IO_ERROR, "Failed to read /etc/os-release", e);
    }
  }

  private String osReleaseValue(String content, String key) {
    return content
        .lines()
        .filter(line -> line.startsWith(key + "="))
        .map(line -> line.substring(key.length() + 1))
        .map(value -> value.replace("\"", ""))
        .findFirst()
        .orElse("");
  }

  private Preset parsePreset(String requestedPreset) {
    return switch (requestedPreset.toLowerCase(Locale.ROOT)) {
      case "minimal" -> Preset.MINIMAL;
      case "developer" -> Preset.DEVELOPER;
      case "desktop" -> Preset.DESKTOP;
      case "dotfiles" -> Preset.DOTFILES;
      default ->
          throw new CliFailureException(
              ExitCode.INVALID_INPUT, "Unsupported generator preset: " + requestedPreset);
    };
  }

  private enum Preset {
    MINIMAL {
      @Override
      String jobs(Target target) {
        return packageJob("system-foundation", "core-cli-tools", target, "git", "curl", "wget");
      }
    },
    DEVELOPER {
      @Override
      String jobs(Target target) {
        return packageJob(
            "system-foundation",
            "developer-tools",
            target,
            "git",
            "curl",
            "wget",
            "ripgrep",
            "fd",
            "jq",
            "zsh");
      }
    },
    DESKTOP {
      @Override
      String jobs(Target target) {
        return packageJob(
                "system-foundation",
                "desktop-tools",
                target,
                "git",
                "curl",
                "wget",
                "flatpak",
                "zsh")
            + """

              - name: desktop-apps
                description: "Install starter desktop Flatpaks"
                dependsOn:
                  - system-foundation
                restartPolicy:
                  type: none
                continueOnModuleError: true
                steps:
                  - type: flatpak-remote
                    name: flathub-remote
                    remote: flathub
                    url: https://flathub.org/repo/flathub.flatpakrepo

                  - type: flatpak
                    name: starter-flatpaks
                    remote: flathub
                    appIds:
                      - org.mozilla.firefox
            """;
      }
    },
    DOTFILES {
      @Override
      String jobs(Target target) {
        return packageJob("system-foundation", "dotfiles-tools", target, "git", "curl", "zsh")
            + """

              - name: dotfiles
                description: "Apply dotfiles with dotbot-go"
                dependsOn:
                  - system-foundation
                restartPolicy:
                  type: none
                continueOnModuleError: false
                steps:
                  - type: dotbot
                    name: dotfiles-core
                    installerVersion: "v0.2.1"
                    config: "~/.dotfiles/install.conf.yaml"
                    dotbotBinary: dotbot
                    probeCommand: "test -f ~/.zshrc"
            """;
      }
    };

    abstract String jobs(Target target);

    static String packageJob(String jobName, String moduleName, Target target, String... packages) {
      return """
        - name: %s
          description: "Install starter packages"
          restartPolicy:
            type: none
          continueOnModuleError: true
          steps:
            - type: packages
              name: %s
              packageManager: %s
              continueOnError: true
              packages:
      %s\
      """
          .formatted(jobName, moduleName, target.packageManager(), packageList(packages));
    }

    private static String packageList(String... packages) {
      StringBuilder builder = new StringBuilder();
      for (String packageName : packages) {
        builder.append("          - ").append(packageName).append('\n');
      }
      return builder.toString();
    }
  }

  private record Target(String osType, String packageManager, String release) {
    String releaseYaml() {
      return release.isBlank() ? "" : "%n  release: \"%s\"".formatted(release);
    }
  }
}
