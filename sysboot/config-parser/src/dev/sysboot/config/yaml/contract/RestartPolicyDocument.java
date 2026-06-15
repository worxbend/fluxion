package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RestartPolicyDocument.NoneDocument.class, name = "none"),
  @JsonSubTypes.Type(value = RestartPolicyDocument.PromptLogoutDocument.class, name = "prompt-logout"),
  @JsonSubTypes.Type(
      value = RestartPolicyDocument.RequiresNewShellDocument.class,
      name = "requires-new-shell")
})
public abstract sealed class RestartPolicyDocument
    permits RestartPolicyDocument.NoneDocument,
        RestartPolicyDocument.PromptLogoutDocument,
        RestartPolicyDocument.RequiresNewShellDocument {

  public static final class NoneDocument extends RestartPolicyDocument {}

  public static final class PromptLogoutDocument extends RestartPolicyDocument {
    @JsonProperty("message")
    public String message;
  }

  public static final class RequiresNewShellDocument extends RestartPolicyDocument {
    @JsonProperty("shell")
    public String shell;
  }
}
