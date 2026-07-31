package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.HostFacts;
import dev.sysboot.core.HostFactsProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkstationProfileWhenEvaluatorTest {

  @Test
  void select_whenUnsupportedGuardsArePresent_rejectsInsteadOfExecutingEntry() throws IOException {
    var document =
        objectMapper.readValue(unsupportedConditionsProfile(), WorkstationProfileDocument.class);
    var hostFacts =
        new FakeHostFactsProvider(
            new HostFacts(
                "linux", Optional.of("fedora"), Optional.of("44"), Optional.empty(), "amd64"),
            Set.of());
    var evaluator = new WorkstationProfileWhenEvaluator(hostFacts);
    var entries = document.spec().orElseThrow().plan();

    assertThatThrownBy(() -> evaluator.select(entries))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported when conditions")
        .hasMessageContaining("files")
        .hasMessageContaining("vars")
        .hasMessageContaining("expression");
  }

  @Test
  void load_whenItemUsesUnsupportedGuard_rejectsBeforeMappingExecutableWork(@TempDir Path directory)
      throws IOException {
    Path profile = directory.resolve("item-guard.yaml");
    Files.writeString(
        profile,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: unsupported-item-condition
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: guarded-commands
              kind: commands
              spec:
                commands:
                  - name: guarded
                    run: "touch /tmp/must-not-run"
                    when:
                      files: [/root/guard]
        """);

    assertThatThrownBy(() -> new YamlConfigLoader().load(profile))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("Unsupported when conditions")
        .hasMessageContaining("files");
  }

  private final ObjectMapper objectMapper =
      new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

  @Test
  void select_whenConditionsDoNotMatch_returnsStableSkipReasons() throws IOException {
    WorkstationProfileDocument document =
        objectMapper.readValue(profile(), WorkstationProfileDocument.class);
    var hostFacts =
        new FakeHostFactsProvider(
            new HostFacts(
                "linux", Optional.of("fedora"), Optional.of("44"), Optional.empty(), "amd64"),
            Set.of("dnf"));
    var evaluator = new WorkstationProfileWhenEvaluator(hostFacts);

    var selection = evaluator.select(document.spec().orElseThrow().plan());

    assertThat(selection.selected())
        .extracting(entry -> entry.name().orElseThrow())
        .containsExactly("fedora-dnf");
    assertThat(selection.skipped())
        .extracting(WorkstationProfileWhenEvaluator.SkippedPlanEntry::reason)
        .containsExactly(
            "when.distribution expected one of [debian, ubuntu] but was fedora",
            "when.commandExists expected 'apt' on PATH");
  }

  private String profile() {
    return """
    apiVersion: initkit.io/v1alpha1
    kind: WorkstationProfile
    metadata:
      name: reason-test
    spec:
      target:
        os:
          distribution: fedora
          release: "44"
      plan:
        - name: debian-apt
          kind: apt-packages
          when:
            distribution:
              oneOf: [debian, ubuntu]
          spec:
            packages: [curl]
        - name: missing-apt
          kind: apt-packages
          when:
            commandExists: apt
          spec:
            packages: [git]
        - name: fedora-dnf
          kind: dnf-packages
          when:
            distribution: fedora
            commandExists: dnf
          spec:
            packages: [ripgrep]
    """;
  }

  private String unsupportedConditionsProfile() {
    return """
    apiVersion: initkit.io/v1alpha1
    kind: WorkstationProfile
    metadata:
      name: unsupported-conditions
    spec:
      target:
        os:
          distribution: fedora
          release: "44"
      plan:
        - name: guarded
          kind: dnf-packages
          when:
            files: [/root/guard]
            vars: {ALLOW: "true"}
            expression: "ALLOW == true"
          spec:
            packages: [curl]
    """;
  }

  private static final class FakeHostFactsProvider implements HostFactsProvider {
    private final HostFacts facts;
    private final Set<String> commands;

    private FakeHostFactsProvider(HostFacts facts, Set<String> commands) {
      this.facts = facts;
      this.commands = Set.copyOf(commands);
    }

    @Override
    public HostFacts facts() {
      return facts;
    }

    @Override
    public boolean commandExists(String command) {
      return commands.contains(command);
    }
  }
}
