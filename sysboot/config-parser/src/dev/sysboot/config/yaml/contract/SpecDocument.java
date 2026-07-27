package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SpecDocument {

  @JsonProperty("target")
  private TargetDocument target;

  @JsonProperty("policy")
  private PolicyDocument policy;

  @JsonProperty("vars")
  private Map<String, String> vars;

  @JsonProperty("sources")
  private SourcesDocument sources;

  @JsonProperty("plan")
  private List<PlanEntryDocument> plan;

  public Optional<TargetDocument> target() {
    return DocumentDefaults.optional(target);
  }

  public Optional<PolicyDocument> policy() {
    return DocumentDefaults.optional(policy);
  }

  public Map<String, String> vars() {
    return DocumentDefaults.map(vars);
  }

  public Optional<SourcesDocument> sources() {
    return DocumentDefaults.optional(sources);
  }

  public List<PlanEntryDocument> plan() {
    return DocumentDefaults.list(plan);
  }
}
