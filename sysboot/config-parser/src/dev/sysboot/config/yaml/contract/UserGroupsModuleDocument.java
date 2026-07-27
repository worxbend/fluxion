package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A {@code user-groups} step: add a user to supplementary Unix groups. */
public final class UserGroupsModuleDocument extends ModuleDocument {

  /** Account to modify. Omitted means the user running Fluxion. */
  @JsonProperty("user")
  public String user;

  @JsonProperty("groups")
  public List<String> groups;

  /** Create a group that does not exist rather than failing. */
  @JsonProperty("createMissing")
  public boolean createMissing;

  /** Raise a logout checkpoint when the current session has not picked the groups up. */
  @JsonProperty("logoutCheckpoint")
  public boolean logoutCheckpoint = true;

  @JsonProperty("checkpointMessage")
  public String checkpointMessage;

  @JsonProperty("continueOnError")
  public boolean continueOnError;
}
