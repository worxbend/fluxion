package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PackagesModuleDocument.class, name = "packages"),
  @JsonSubTypes.Type(value = AptRepositoryModuleDocument.class, name = "apt-repository"),
  @JsonSubTypes.Type(value = FlatpakModuleDocument.class, name = "flatpak"),
  @JsonSubTypes.Type(value = FlatpakRemoteModuleDocument.class, name = "flatpak-remote"),
  @JsonSubTypes.Type(value = ShellScriptModuleDocument.class, name = "shell-script"),
  @JsonSubTypes.Type(value = CompiledBinaryModuleDocument.class, name = "compiled-binary"),
  @JsonSubTypes.Type(value = DotbotModuleDocument.class, name = "dotbot"),
  @JsonSubTypes.Type(value = DefaultShellModuleDocument.class, name = "default-shell"),
  @JsonSubTypes.Type(value = OhMyZshModuleDocument.class, name = "oh-my-zsh"),
  @JsonSubTypes.Type(value = ToolchainModuleDocument.class, name = "toolchain"),
  @JsonSubTypes.Type(value = NerdFontModuleDocument.class, name = "nerd-fonts"),
  @JsonSubTypes.Type(value = ShellReloadModuleDocument.class, name = "shell-reload"),
  @JsonSubTypes.Type(value = ShellCommandModuleDocument.class, name = "shell-command"),
  @JsonSubTypes.Type(value = AssertModuleDocument.class, name = "assert"),
  @JsonSubTypes.Type(value = ManualModuleDocument.class, name = "manual")
})
public abstract sealed class ModuleDocument
    permits PackagesModuleDocument,
        AptRepositoryModuleDocument,
        FlatpakModuleDocument,
        FlatpakRemoteModuleDocument,
        ShellScriptModuleDocument,
        CompiledBinaryModuleDocument,
        DotbotModuleDocument,
        DefaultShellModuleDocument,
        OhMyZshModuleDocument,
        ToolchainModuleDocument,
        NerdFontModuleDocument,
        ShellReloadModuleDocument,
        ShellCommandModuleDocument,
        AssertModuleDocument,
        ManualModuleDocument {

  @JsonProperty("name")
  public String name;
}
