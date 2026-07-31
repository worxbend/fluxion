package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.PacmanRepositorySourceSetup;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PublicUrl;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellEnvironmentVariable;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperModule;
import dev.sysboot.core.ZypperRepositoryModule;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

final class PhaseFingerprintCalculator {

  String fingerprint(Phase phase) {
    var builder = new StringBuilder();
    append(builder, "phase", phase.name().value());
    append(builder, "description", phase.description());
    append(builder, "continueOnModuleError", phase.continueOnModuleError());
    phase.dependsOn().forEach(dep -> append(builder, "dependsOn", dep.value()));
    appendRestartPolicy(builder, phase.restartPolicy());
    phase.modules().forEach(module -> appendModule(builder, module));
    return sha256(builder.toString());
  }

  String manifestFingerprint(BootstrapConfig config) {
    var builder = new StringBuilder();
    append(builder, "profile", config.profileName().value());
    append(builder, "target", config.target().toString());
    append(builder, "dryRun", config.policy().dryRunDefault().map(Object::toString));
    append(
        builder, "continueOnError", config.policy().continueOnErrorDefault().map(Object::toString));
    append(builder, "requireSudo", config.policy().requireSudoDefault().map(Object::toString));
    config.sourceSetups().forEach(setup -> appendSourceSetup(builder, setup));
    config.phases().forEach(phase -> append(builder, "phaseFingerprint", fingerprint(phase)));
    return sha256(builder.toString());
  }

  private void appendRestartPolicy(StringBuilder builder, RestartPolicy policy) {
    switch (policy) {
      case RestartPolicy.None ignored -> append(builder, "restart", "none");
      case RestartPolicy.PromptLogout prompt -> {
        append(builder, "restart", "prompt-logout");
        append(builder, "message", prompt.message());
      }
      case RestartPolicy.RequiresNewShell shell -> {
        append(builder, "restart", "requires-new-shell");
        append(builder, "shell", shell.shell().name());
      }
    }
  }

  private void appendModule(StringBuilder builder, BootstrapModule module) {
    append(builder, "module", module.name().value());
    switch (module) {
      case PackageModule pm -> appendPackageModule(builder, pm);
      case ZypperModule zm -> appendPackageModule(builder, zm.asPackageModule());
      case AptRepositoryModule arm -> appendAptRepository(builder, arm);
      case RpmRepositoryModule rrm -> appendRpmRepository(builder, rrm);
      case PacmanRepositoryModule prm -> appendPacmanRepository(builder, prm);
      case FileWriteModule fwm -> appendFileWrite(builder, fwm);
      case FlatpakModule fm -> {
        append(builder, "type", "flatpak");
        append(builder, "remote", fm.remote());
        fm.appIds().forEach(app -> append(builder, "appId", app));
        append(builder, "continueOnError", fm.continueOnError());
      }
      case FlatpakRemoteModule frm -> {
        append(builder, "type", "flatpak-remote");
        append(builder, "remote", frm.remote());
        append(builder, "url", PublicUrl.from(frm.url()));
        append(builder, "system", frm.system());
        append(builder, "artifactSha256", frm.artifactSha256().map(Sha256Digest::value));
      }
      case ShellScriptModule sm -> {
        append(builder, "type", "shell-script");
        sm.items().forEach(item -> appendShellScriptItem(builder, item));
        append(builder, "workingDir", sm.workingDir().map(Object::toString));
        append(builder, "continueOnError", sm.continueOnError());
        append(builder, "probe", sm.probeCommand());
      }
      case CompiledBinaryModule bm -> appendCompiledBinary(builder, bm);
      case DotbotModule dm -> {
        append(builder, "type", "dotbot");
        append(builder, "config", dm.config().toString());
        appendLocalFileDigest(builder, "configContentSha256", dm.config());
        append(builder, "installerVersion", dm.installerVersion());
        append(builder, "binary", dm.dotbotBinary());
        append(builder, "probe", dm.probeCommand());
      }
      case DefaultShellModule dsm -> {
        append(builder, "type", "default-shell");
        append(builder, "shellPath", dsm.shellPath().toString());
        append(builder, "probe", dsm.probeCommand());
      }
      case OhMyZshModule omz -> {
        append(builder, "type", "oh-my-zsh");
        append(builder, "installDir", omz.installDir().toString());
        append(builder, "installerRevision", omz.installerRevision());
        append(builder, "installerSha256", omz.installerSha256().value());
        append(builder, "probe", omz.probeCommand());
      }
      case ToolchainModule tm -> appendToolchain(builder, tm);
      case NerdFontModule nfm -> appendNerdFont(builder, nfm);
      case ShellReloadModule srm -> {
        append(builder, "type", "shell-reload");
        append(builder, "shell", srm.shell().name());
        append(builder, "description", srm.description());
      }
      case ShellCommandModule scm -> {
        append(builder, "type", "shell-command");
        scm.items().forEach(item -> appendShellCommandItem(builder, item));
        append(builder, "shell", scm.shell());
        append(builder, "workingDir", scm.workingDir().map(Object::toString));
        append(builder, "continueOnError", scm.continueOnError());
        append(builder, "probe", scm.probeCommand());
      }
      case AssertModule am -> {
        append(builder, "type", "assert");
        append(builder, "command", am.command());
        append(builder, "message", am.message());
        append(builder, "shell", am.shell());
        append(builder, "workingDir", am.workingDir().map(Object::toString));
      }
      case ManualModule mm -> {
        append(builder, "type", "manual");
        append(builder, "message", mm.message());
        append(builder, "probe", mm.probeCommand());
      }
      case InterruptModule im -> {
        append(builder, "type", "interrupt");
        append(builder, "message", im.message());
        im.instructions().forEach(instruction -> append(builder, "instruction", instruction));
        append(builder, "resumeFrom", im.resumeFrom().name());
        append(builder, "exitCode", Integer.toString(im.exitCode()));
      }
      case SdkmanModule sm -> {
        append(builder, "type", "sdkman-packages");
        sm.packages().forEach(pkg -> append(builder, "package", pkg.itemKey()));
        append(builder, "continueOnError", sm.continueOnError());
      }
      case GitConfigModule gcm -> {
        append(builder, "type", "git-config");
        append(builder, "scope", gcm.scope().name());
        gcm.sortedKeys().forEach(key -> append(builder, key, gcm.entries().get(key)));
        append(builder, "continueOnError", gcm.continueOnError());
      }
      case GitRepoModule grm -> {
        append(builder, "type", "git-repo");
        grm.repos()
            .forEach(
                repo -> {
                  append(builder, "url", PublicUrl.from(repo.url()));
                  append(builder, "destination", repo.destination());
                  repo.ref().ifPresent(ref -> append(builder, "ref", ref));
                  append(builder, "depth", repo.depth().map(Object::toString));
                  append(builder, "submodules", repo.submodules());
                  append(builder, "update", repo.update().name());
                });
        append(builder, "continueOnError", grm.continueOnError());
      }
      case SystemdUnitModule sum -> {
        append(builder, "type", "systemd-unit");
        append(builder, "scope", sum.scope().name());
        sum.units()
            .forEach(
                unit -> {
                  append(builder, "unit", unit.qualifiedName());
                  append(builder, "enabled", unit.enabled());
                  append(builder, "state", unit.state().name());
                  append(builder, "masked", unit.masked());
                });
        append(builder, "continueOnError", sum.continueOnError());
      }
      case SystemSettingModule ssm -> {
        append(builder, "type", "system-setting");
        ssm.localRtc().ifPresent(v -> append(builder, "localRtc", v));
        ssm.ntp().ifPresent(v -> append(builder, "ntp", v));
        ssm.timezone().ifPresent(v -> append(builder, "timezone", v));
        ssm.hostname().ifPresent(v -> append(builder, "hostname", v));
        ssm.locale().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> append(builder, entry.getKey(), entry.getValue()));
        append(builder, "continueOnError", ssm.continueOnError());
      }
      case SystemUpdateModule sup -> {
        append(builder, "type", "system-update");
        append(builder, "packageManager", sup.packageManager().name());
        append(builder, "distUpgrade", sup.distUpgrade());
        append(builder, "refreshOnly", sup.refreshOnly());
        append(builder, "timeout", sup.timeout().map(Object::toString));
        append(builder, "continueOnError", sup.continueOnError());
      }
      case GpgKeyModule gkm -> {
        append(builder, "type", "gpg-key");
        gkm.keys()
            .forEach(
                key -> {
                  append(builder, "url", PublicUrl.from(key.url()));
                  key.keyring().ifPresent(ring -> append(builder, "keyring", ring.toString()));
                  append(builder, "fingerprint", key.fingerprint());
                });
        append(builder, "continueOnError", gkm.continueOnError());
      }
      case ToolPackagesModule tpm -> {
        append(builder, "type", "tool-packages");
        append(builder, "backend", tpm.backend().id());
        tpm.packages()
            .forEach(
                pkg ->
                    append(
                        builder,
                        "package",
                        pkg.name() + pkg.version().map(v -> "@" + v).orElse("")));
        append(builder, "continueOnError", tpm.continueOnError());
      }
      case ZypperRepositoryModule zrm -> {
        append(builder, "type", "zypper-repository");
        append(builder, "id", zrm.repositoryId());
        append(builder, "baseUrl", PublicUrl.from(zrm.baseUrl()));
        append(builder, "repoFile", zrm.repoFilePath().toString());
        zrm.gpgKeyUrl().ifPresent(url -> append(builder, "gpgKeyUrl", PublicUrl.from(url)));
        append(builder, "artifactSha256", zrm.artifactSha256().map(Sha256Digest::value));
        append(builder, "enabled", zrm.enabled());
        append(builder, "gpgCheck", zrm.gpgCheck());
        append(builder, "autoRefresh", zrm.autoRefresh());
      }
      case UserGroupsModule ugm -> {
        append(builder, "type", "user-groups");
        ugm.user().ifPresent(user -> append(builder, "user", user));
        ugm.groups().forEach(group -> append(builder, "group", group));
        append(builder, "createMissing", ugm.createMissing());
        append(builder, "logoutCheckpoint", ugm.logoutCheckpoint());
        append(builder, "checkpointMessage", ugm.checkpointMessage());
        append(builder, "continueOnError", ugm.continueOnError());
      }
      case BinstallerModule bsm -> {
        append(builder, "type", "binstaller-profile");
        append(builder, "config", bsm.config().toString());
        appendLocalFileDigest(builder, "configContentSha256", bsm.config());
        bsm.only().forEach(tool -> append(builder, "only", tool));
        bsm.skip().forEach(tool -> append(builder, "skip", tool));
        append(builder, "locked", bsm.locked());
        bsm.lockFile()
            .ifPresent(
                path -> {
                  append(builder, "lockFile", path.toString());
                  appendLocalFileDigest(builder, "lockFileContentSha256", path);
                });
        append(builder, "installerVersion", bsm.installerVersion());
        append(builder, "binary", bsm.binstallerBinary());
        append(builder, "probe", bsm.probeCommand());
        append(builder, "continueOnError", bsm.continueOnError());
      }
    }
  }

  private void appendPackageModule(StringBuilder builder, PackageModule module) {
    append(builder, "type", "packages");
    append(builder, "packageManager", module.packageManager().name());
    append(builder, "continueOnError", module.continueOnError());
    module.actions().forEach(action -> appendPackageAction(builder, action));
    module.packages().forEach(pkg -> append(builder, "package", pkg.value()));
  }

  private void appendPackageAction(
      StringBuilder builder, dev.sysboot.core.PackageManagerAction action) {
    append(builder, "action", action.action());
    action.args().forEach(arg -> append(builder, "actionArg", arg));
  }

  private void appendShellScriptItem(StringBuilder builder, dev.sysboot.core.ShellScriptItem item) {
    append(builder, "scriptItem", item.name());
    append(builder, "script", item.script().map(Object::toString));
    item.script()
        .ifPresent(script -> appendLocalFileDigest(builder, "scriptContentSha256", script.value()));
    append(builder, "url", item.url().map(PublicUrl::from));
    item.args().forEach(arg -> append(builder, "arg", arg));
    append(builder, "cwd", item.workingDir().map(Object::toString));
    append(builder, "sudo", item.sudo());
    item.allowedExitCodes().forEach(code -> append(builder, "allowedExit", code.toString()));
    append(builder, "creates", item.creates().map(Object::toString));
    append(builder, "unless", item.unless());
    append(builder, "confirm", item.confirm());
    append(builder, "timeout", item.timeout().toString());
    appendShellScriptEnvironment(builder, item);
    append(builder, "sha256", item.sha256().map(dev.sysboot.core.Sha256Digest::value));
  }

  private void appendShellScriptEnvironment(
      StringBuilder builder, dev.sysboot.core.ShellScriptItem item) {
    item.environment().stream()
        .sorted(Comparator.comparing(ShellEnvironmentVariable::name))
        .forEach(
            variable -> {
              append(builder, "environmentName", variable.name());
              appendEnvironmentValue(builder, variable);
              append(builder, "environmentSensitive", variable.sensitive());
            });
  }

  private void appendShellCommandItem(
      StringBuilder builder, dev.sysboot.core.ShellCommandItem item) {
    append(builder, "commandItem", item.name());
    append(builder, "shellCommand", item.shellCommand());
    item.argv().ifPresent(argv -> argv.forEach(arg -> append(builder, "argv", arg)));
    append(builder, "shell", item.shell());
    append(builder, "cwd", item.workingDir().map(Object::toString));
    append(builder, "sudo", item.sudo());
    item.allowedExitCodes().forEach(code -> append(builder, "allowedExit", code.toString()));
    append(builder, "creates", item.creates().map(Object::toString));
    append(builder, "unless", item.unless());
    append(builder, "confirm", item.confirm());
    append(builder, "timeout", item.timeout().toString());
    appendShellCommandEnvironment(builder, item);
  }

  private void appendShellCommandEnvironment(
      StringBuilder builder, dev.sysboot.core.ShellCommandItem item) {
    item.environment().stream()
        .sorted(Comparator.comparing(ShellEnvironmentVariable::name))
        .forEach(
            variable -> {
              append(builder, "environmentName", variable.name());
              appendEnvironmentValue(builder, variable);
              append(builder, "environmentSensitive", variable.sensitive());
            });
  }

  private void appendAptRepository(StringBuilder builder, AptRepositoryModule module) {
    append(builder, "type", "apt-repository");
    append(builder, "source", module.sourceEntry());
    append(builder, "sourceList", module.sourceListPath().toString());
    append(builder, "signingKeyUrl", module.signingKeyUrl().map(PublicUrl::from));
    append(builder, "keyring", module.keyringPath().map(Object::toString));
    append(builder, "artifactSha256", module.artifactSha256().map(Sha256Digest::value));
  }

  private void appendRpmRepository(StringBuilder builder, RpmRepositoryModule module) {
    append(builder, "type", "rpm-repository");
    append(builder, "id", module.repositoryId());
    append(builder, "baseUrl", PublicUrl.from(module.baseUrl()));
    append(builder, "repoFile", module.repoFilePath().toString());
    append(builder, "gpgKeyUrl", module.gpgKeyUrl().map(PublicUrl::from));
    append(builder, "artifactSha256", module.artifactSha256().map(Sha256Digest::value));
    append(builder, "enabled", module.enabled());
    append(builder, "gpgCheck", module.gpgCheck());
  }

  private void appendPacmanRepository(StringBuilder builder, PacmanRepositoryModule module) {
    append(builder, "type", "pacman-repository");
    append(builder, "repository", module.repositoryName());
    append(builder, "server", PublicUrl.from(module.server()));
    append(builder, "config", module.configPath().toString());
    append(builder, "sigLevel", module.sigLevel());
    append(builder, "include", module.include().map(Object::toString));
    append(builder, "enabled", module.enabled());
  }

  private void appendFileWrite(StringBuilder builder, FileWriteModule module) {
    append(builder, "type", "file-writes");
    module
        .items()
        .forEach(
            item -> {
              append(builder, "file", item.name());
              append(builder, "destination", item.destination().toString());
              append(builder, "content", item.content().map(this::sha256));
              append(builder, "source", item.source().map(Path::toString));
              item.source()
                  .ifPresent(
                      source -> appendLocalFileDigest(builder, "sourceContentSha256", source));
              append(builder, "owner", item.owner());
              append(builder, "group", item.group());
              append(builder, "mode", item.mode());
              append(builder, "sudo", item.sudo());
            });
    append(builder, "continueOnError", module.continueOnError());
  }

  private void appendCompiledBinary(StringBuilder builder, CompiledBinaryModule module) {
    append(builder, "type", "compiled-binary");
    append(builder, "binaryName", module.binaryName());
    append(builder, "url", module.url().stateSource());
    append(builder, "checksum", module.checksum().map(Object::toString));
    append(
        builder, "checksumUrl", module.checksumUrl().map(dev.sysboot.core.BinaryUrl::stateSource));
    append(
        builder,
        "signatureUrl",
        module.signatureUrl().map(dev.sysboot.core.BinaryUrl::stateSource));
    append(builder, "allowedSignerFingerprint", module.allowedSignerFingerprint());
    append(builder, "installPath", module.installPath().toString());
    append(builder, "archivePath", module.archivePath());
    append(builder, "stripComponents", Integer.toString(module.stripComponents()));
    append(builder, "installMode", module.installMode());
    append(builder, "symlinkPath", module.symlinkPath().map(Path::toString));
    append(builder, "continueOnError", module.continueOnError());
    append(builder, "versionCommand", module.versionCommand());
    append(builder, "expectedVersion", module.expectedVersion());
  }

  private void appendToolchain(StringBuilder builder, ToolchainModule module) {
    append(builder, "type", "toolchain");
    append(builder, "kind", module.kind().name());
    append(builder, "installScript", PublicUrl.from(module.installScript()));
    append(builder, "installScriptSha256", module.installScriptSha256().value());
    module.installArgs().forEach(arg -> append(builder, "arg", arg));
    append(builder, "postInstallEnvSource", module.postInstallEnvSource());
    append(builder, "probe", module.probeCommand());
    append(builder, "continueOnError", module.continueOnError());
  }

  private void appendNerdFont(StringBuilder builder, NerdFontModule module) {
    append(builder, "type", "nerd-fonts");
    append(builder, "installerVersion", module.installerVersion());
    append(builder, "binary", module.nerdfontBinary());
    append(builder, "release", module.config().release());
    append(builder, "destination", module.config().destination().toString());
    append(builder, "refreshFontCache", module.config().refreshFontCache());
    module.config().families().forEach(family -> append(builder, "family", family));
    append(builder, "configPath", module.configPath().map(Path::toString));
    module
        .configPath()
        .ifPresent(path -> appendLocalFileDigest(builder, "configContentSha256", path));
    append(builder, "probe", module.probeCommand());
  }

  private void appendEnvironmentValue(StringBuilder builder, ShellEnvironmentVariable variable) {
    append(
        builder,
        "environmentValue",
        variable.sensitive()
            ? sha256(
                "fluxion:sensitive-environment:v1\u0000"
                    + variable.name()
                    + "\u0000"
                    + variable.value())
            : variable.value());
  }

  private void appendLocalFileDigest(StringBuilder builder, String key, Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      append(builder, key, "<missing>");
      return;
    }
    try {
      append(builder, key, ArtifactDigests.sha256(path, 64L * 1024L * 1024L).value());
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to fingerprint local input: " + path, e);
    }
  }

  private void appendSourceSetup(StringBuilder builder, SourceSetup setup) {
    append(builder, "sourceSetup", setup.name().value());
    switch (setup) {
      case AptRepositorySourceSetup apt -> {
        String requestUrl =
            dev.sysboot.core.SourceUrlPolicy.aptRepositoryUri(apt.sourceEntry()).toString();
        append(
            builder, "source", apt.sourceEntry().replace(requestUrl, PublicUrl.from(requestUrl)));
        append(builder, "sourceList", apt.sourceListPath().toString());
        append(builder, "signingKeyUrl", apt.signingKeyUrl().map(PublicUrl::from));
        append(builder, "keyring", apt.keyringPath().map(Path::toString));
        append(builder, "artifactSha256", apt.artifactSha256().map(Sha256Digest::value));
      }
      case RpmRepositorySourceSetup rpm -> {
        append(builder, "id", rpm.repositoryId());
        append(builder, "baseUrl", PublicUrl.from(rpm.baseUrl()));
        append(builder, "repoFile", rpm.repoFilePath().toString());
        append(builder, "gpgKeyUrl", rpm.gpgKeyUrl().map(PublicUrl::from));
        append(builder, "enabled", rpm.enabled());
        append(builder, "gpgCheck", rpm.gpgCheck());
        append(builder, "artifactSha256", rpm.artifactSha256().map(Sha256Digest::value));
      }
      case ZypperRepositorySourceSetup zypper -> {
        append(builder, "id", zypper.repositoryId());
        append(builder, "baseUrl", PublicUrl.from(zypper.baseUrl()));
        append(builder, "repoFile", zypper.repoFilePath().toString());
        append(builder, "gpgKeyUrl", zypper.gpgKeyUrl().map(PublicUrl::from));
        append(builder, "enabled", zypper.enabled());
        append(builder, "gpgCheck", zypper.gpgCheck());
        append(builder, "autoRefresh", zypper.autoRefresh());
        append(builder, "artifactSha256", zypper.artifactSha256().map(Sha256Digest::value));
      }
      case PacmanRepositorySourceSetup pacman -> {
        append(builder, "repository", pacman.repositoryName());
        append(builder, "server", PublicUrl.from(pacman.server()));
        append(builder, "config", pacman.configPath().toString());
        append(builder, "sigLevel", pacman.sigLevel());
        append(builder, "include", pacman.include().map(Path::toString));
        append(builder, "enabled", pacman.enabled());
      }
      case FlatpakRemoteSourceSetup flatpak -> {
        append(builder, "remote", flatpak.remote());
        append(builder, "url", PublicUrl.from(flatpak.url()));
        append(builder, "system", flatpak.system());
        append(builder, "artifactSha256", flatpak.artifactSha256().map(Sha256Digest::value));
      }
    }
  }

  private void append(StringBuilder builder, String key, Optional<String> value) {
    append(builder, key, value.orElse(""));
  }

  private void append(StringBuilder builder, String key, boolean value) {
    append(builder, key, Boolean.toString(value));
  }

  private void append(StringBuilder builder, String key, String value) {
    builder
        .append(key.length())
        .append(':')
        .append(key)
        .append(value.length())
        .append(':')
        .append(value);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is unavailable", e);
    }
  }
}
