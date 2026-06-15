package dev.sysboot.executor;

import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
      case FlatpakModule fm -> {
        append(builder, "type", "flatpak");
        append(builder, "remote", fm.remote());
        fm.appIds().forEach(app -> append(builder, "appId", app));
      }
      case ShellScriptModule sm -> {
        append(builder, "type", "shell-script");
        append(builder, "script", sm.script().toString());
        sm.args().forEach(arg -> append(builder, "arg", arg));
        append(builder, "workingDir", sm.workingDir().map(Object::toString));
        append(builder, "continueOnError", sm.continueOnError());
        append(builder, "probe", sm.probeCommand());
      }
      case CompiledBinaryModule bm -> appendCompiledBinary(builder, bm);
      case DotbotModule dm -> {
        append(builder, "type", "dotbot");
        append(builder, "config", dm.config().toString());
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
        scm.commands().forEach(command -> append(builder, "command", command));
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
    }
  }

  private void appendPackageModule(StringBuilder builder, PackageModule module) {
    append(builder, "type", "packages");
    append(builder, "packageManager", module.packageManager().name());
    append(builder, "continueOnError", module.continueOnError());
    module.packages().forEach(pkg -> append(builder, "package", pkg.value()));
  }

  private void appendCompiledBinary(StringBuilder builder, CompiledBinaryModule module) {
    append(builder, "type", "compiled-binary");
    append(builder, "binaryName", module.binaryName());
    append(builder, "url", module.url().toString());
    append(builder, "checksum", module.checksum().map(Object::toString));
    append(builder, "checksumUrl", module.checksumUrl().map(Object::toString));
    append(builder, "installPath", module.installPath().toString());
    append(builder, "continueOnError", module.continueOnError());
    append(builder, "versionCommand", module.versionCommand());
    append(builder, "expectedVersion", module.expectedVersion());
  }

  private void appendToolchain(StringBuilder builder, ToolchainModule module) {
    append(builder, "type", "toolchain");
    append(builder, "kind", module.kind().name());
    append(builder, "installScript", module.installScript());
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
    append(builder, "probe", module.probeCommand());
  }

  private void append(StringBuilder builder, String key, Optional<String> value) {
    append(builder, key, value.orElse(""));
  }

  private void append(StringBuilder builder, String key, boolean value) {
    append(builder, key, Boolean.toString(value));
  }

  private void append(StringBuilder builder, String key, String value) {
    builder.append(key).append('=').append(value).append('\n');
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
