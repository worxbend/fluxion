package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
import dev.sysboot.core.PackageModule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkstationProfileExtractionCharacterizationTest {

  @TempDir Path tempDirectory;

  @Test
  void validation_preservesHeaderMetadataThenPlanErrorOrder() throws Exception {
    var mapper = new ObjectMapper(new YAMLFactory());
    WorkstationProfileDocument document =
        mapper.readValue(
            """
            apiVersion: bad-version
            kind: WrongKind
            metadata:
              name: " "
            spec:
              plan:
                - name: duplicate
                  kind: commands
                  spec:
                    commands: []
                - name: duplicate
                  kind: apt-package
            """,
            WorkstationProfileDocument.class);

    assertThatThrownBy(
            () ->
                new WorkstationProfileValidator()
                    .validate(document, tempDirectory.resolve("profile.yaml")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "apiVersion must be 'initkit.io/v1alpha1' but was 'bad-version'; "
                + "kind must be 'WorkstationProfile' but was 'WrongKind'; "
                + "metadata.name must not be blank; "
                + "spec.plan[0].spec.commands must contain at least one item; "
                + "spec.plan[1].name duplicates plan entry 'duplicate' first declared at "
                + "spec.plan[0].name; "
                + "spec.plan[1].kind unsupported plan kind 'apt-package'. "
                + "Did you mean 'apt-packages'?");
  }

  @Test
  void mapping_preservesPolicyOverrideAndInterruptDefaults() throws Exception {
    Path manifest = tempDirectory.resolve("profile.yaml");
    Files.writeString(
        manifest,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: extraction-characterization
        spec:
          target:
            os:
              distribution: arch
          policy:
            continueOnError: false
          plan:
            - name: packages
              kind: pacman-packages
              execution:
                continueOnError: true
              spec:
                packages: [git]
            - name: pause
              kind: interrupt
        """);

    var config = new YamlConfigLoader().load(manifest);
    var modules = config.phases().getFirst().modules();

    assertThat(config.policy().continueOnErrorDefault()).contains(false);
    assertThat(((PackageModule) modules.get(0)).continueOnError()).isTrue();
    assertThat((InterruptModule) modules.get(1))
        .satisfies(
            interrupt -> {
              assertThat(interrupt.message())
                  .isEqualTo("Execution paused by interrupt entry: pause");
              assertThat(interrupt.exitCode()).isEqualTo(75);
              assertThat(interrupt.resumeFrom()).isEqualTo(InterruptResumeMode.NEXT);
            });
  }
}
