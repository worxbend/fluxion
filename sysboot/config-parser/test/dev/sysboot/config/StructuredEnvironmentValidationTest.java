package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellEnvironmentVariable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredEnvironmentValidationTest {

  private final YamlConfigLoader loader = new YamlConfigLoader();

  @Test
  void load_whenEnvironmentValuesAreValid_roundTripsTextObjectAndSensitivity(@TempDir Path tempDir)
      throws IOException {
    Path config =
        write(
            tempDir,
            "valid-env.yaml",
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: valid-environment
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: commands
                  kind: commands
                  spec:
                    env:
                      EMPTY: ""
                      API_TOKEN:
                        value: module-token
                        sensitive: false
                    commands:
                      - name: inspect
                        run: [env]
                        env:
                          PLAIN: item-value
                          SECRET:
                            value: ""
                            sensitive: true
            """);

    var module = (ShellCommandModule) loader.load(config).modules().getFirst();
    var environment =
        module.items().getFirst().environment().stream()
            .collect(Collectors.toMap(ShellEnvironmentVariable::name, Function.identity()));

    assertThat(environment.get("EMPTY").value()).isEmpty();
    assertThat(environment.get("API_TOKEN"))
        .extracting(ShellEnvironmentVariable::value, ShellEnvironmentVariable::sensitive)
        .containsExactly("module-token", false);
    assertThat(environment.get("PLAIN").value()).isEqualTo("item-value");
    assertThat(environment.get("SECRET"))
        .extracting(ShellEnvironmentVariable::value, ShellEnvironmentVariable::sensitive)
        .containsExactly("", true);
  }

  @Test
  void load_whenEnvironmentOrItemEnvironmentIsNotObject_reportsExactPaths(@TempDir Path tempDir)
      throws IOException {
    Path config =
        write(
            tempDir,
            "invalid-env-containers.yaml",
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: invalid-environment-containers
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: module-env
                  kind: commands
                  spec:
                    env: [not, an, object]
                    commands: [[env]]
                - name: item-env
                  kind: commands
                  spec:
                    commands:
                      - name: inspect
                        run: [env]
                        env: null
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].spec.env must be an object")
        .hasMessageContaining("spec.plan[1].spec.commands[0].env must be an object");
  }

  @Test
  void load_whenEnvironmentValuesHaveMalformedShapes_reportsEveryFieldPath(@TempDir Path tempDir)
      throws IOException {
    Path config =
        write(
            tempDir,
            "invalid-env-values.yaml",
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: invalid-environment-values
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: commands
                  kind: commands
                  spec:
                    env:
                      NUMBER: 1
                      BOOLEAN: true
                      LIST: [value]
                      NULL_VALUE: null
                      MISSING_VALUE: {}
                      BAD_VALUE: {value: 42}
                      BAD_SENSITIVE: {value: okay, sensitive: "yes"}
                    commands:
                      - name: inspect
                        run: [env]
                        env:
                          ITEM_MISSING: {sensitive: true}
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].spec.env.NUMBER must be a string or object")
        .hasMessageContaining("spec.plan[0].spec.env.BOOLEAN must be a string or object")
        .hasMessageContaining("spec.plan[0].spec.env.LIST must be a string or object")
        .hasMessageContaining("spec.plan[0].spec.env.NULL_VALUE must be a string or object")
        .hasMessageContaining("spec.plan[0].spec.env.MISSING_VALUE.value is required")
        .hasMessageContaining("spec.plan[0].spec.env.BAD_VALUE.value must be a string")
        .hasMessageContaining("spec.plan[0].spec.env.BAD_SENSITIVE.sensitive must be a boolean")
        .hasMessageContaining("spec.plan[0].spec.commands[0].env.ITEM_MISSING.value is required");
  }

  private Path write(Path directory, String fileName, String content) throws IOException {
    Path config = directory.resolve(fileName);
    Files.writeString(config, content);
    return config;
  }
}
