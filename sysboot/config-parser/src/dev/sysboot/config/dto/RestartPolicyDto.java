package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RestartPolicyDto.NoneDto.class, name = "none"),
  @JsonSubTypes.Type(value = RestartPolicyDto.PromptLogoutDto.class, name = "prompt-logout"),
  @JsonSubTypes.Type(
      value = RestartPolicyDto.RequiresNewShellDto.class,
      name = "requires-new-shell")
})
public abstract sealed class RestartPolicyDto
    permits RestartPolicyDto.NoneDto,
        RestartPolicyDto.PromptLogoutDto,
        RestartPolicyDto.RequiresNewShellDto {

  public static final class NoneDto extends RestartPolicyDto {}

  public static final class PromptLogoutDto extends RestartPolicyDto {
    @JsonProperty("message")
    public String message;
  }

  public static final class RequiresNewShellDto extends RestartPolicyDto {
    @JsonProperty("shell")
    public String shell;
  }
}
