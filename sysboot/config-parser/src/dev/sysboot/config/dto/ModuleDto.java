package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PackagesModuleDto.class, name = "packages"),
  @JsonSubTypes.Type(value = FlatpakModuleDto.class, name = "flatpak"),
  @JsonSubTypes.Type(value = ShellScriptModuleDto.class, name = "shell-script"),
  @JsonSubTypes.Type(value = CompiledBinaryModuleDto.class, name = "compiled-binary"),
  @JsonSubTypes.Type(value = DotbotModuleDto.class, name = "dotbot"),
  @JsonSubTypes.Type(value = DefaultShellModuleDto.class, name = "default-shell"),
  @JsonSubTypes.Type(value = OhMyZshModuleDto.class, name = "oh-my-zsh"),
  @JsonSubTypes.Type(value = ToolchainModuleDto.class, name = "toolchain"),
  @JsonSubTypes.Type(value = NerdFontModuleDto.class, name = "nerd-fonts"),
  @JsonSubTypes.Type(value = ShellReloadModuleDto.class, name = "shell-reload"),
  @JsonSubTypes.Type(value = ShellCommandModuleDto.class, name = "shell-command")
})
public abstract sealed class ModuleDto
    permits PackagesModuleDto,
        FlatpakModuleDto,
        ShellScriptModuleDto,
        CompiledBinaryModuleDto,
        DotbotModuleDto,
        DefaultShellModuleDto,
        OhMyZshModuleDto,
        ToolchainModuleDto,
        NerdFontModuleDto,
        ShellReloadModuleDto,
        ShellCommandModuleDto {

  @JsonProperty("name")
  public String name;
}
