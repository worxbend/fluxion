package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BootstrapConfigBuilderTest {

  private static final ProfileName PROFILE = new ProfileName("test-profile");
  private static final OsTarget TARGET = new OsTarget.FedoraTarget("41");
  private static final PackageModule SAMPLE_MODULE =
      new PackageModule(
          new ModuleName("core-tools"),
          PackageManagerKind.DNF,
          List.of(new PackageName("git")),
          true);

  @Test
  void build_whenAllFieldsProvided_createsConfig() {
    var config =
        BootstrapConfig.builder()
            .profileName(PROFILE)
            .target(TARGET)
            .addModule(SAMPLE_MODULE)
            .build();

    assertThat(config.profileName()).isEqualTo(PROFILE);
    assertThat(config.target()).isEqualTo(TARGET);
    assertThat(config.policy()).isEqualTo(BootstrapPolicy.empty());
    assertThat(config.skippedPlanEntries()).isEmpty();
    assertThat(config.sourceSetups()).isEmpty();
    assertThat(config.modules()).hasSize(1);
  }

  @Test
  void build_whenSkippedPlanEntriesProvided_preservesUnmodifiableEntries() {
    var skipped = new SkippedPlanEntry("arch-only", "pacman-packages", "when.os expected arch");

    var config =
        BootstrapConfig.builder()
            .profileName(PROFILE)
            .target(TARGET)
            .skippedPlanEntries(List.of(skipped))
            .addModule(SAMPLE_MODULE)
            .build();

    assertThat(config.skippedPlanEntries()).containsExactly(skipped);
    assertThatThrownBy(() -> config.skippedPlanEntries().add(skipped))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void build_whenSourceSetupsProvided_preservesUnmodifiableEntries() {
    var source =
        new FlatpakRemoteSourceSetup(
            new ModuleName("flathub"),
            "flathub",
            URI.create("https://flathub.org/repo/flathub.flatpakrepo"),
            true);

    var config =
        BootstrapConfig.builder()
            .profileName(PROFILE)
            .target(TARGET)
            .sourceSetups(List.of(source))
            .addModule(SAMPLE_MODULE)
            .build();

    assertThat(config.sourceSetups()).containsExactly(source);
    assertThatThrownBy(() -> config.sourceSetups().add(source))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void build_whenPolicyProvided_preservesPolicyDefaults() {
    var policy =
        new BootstrapPolicy(Optional.of(true), Optional.of(false), Optional.of(false));

    var config =
        BootstrapConfig.builder()
            .profileName(PROFILE)
            .target(TARGET)
            .policy(policy)
            .addModule(SAMPLE_MODULE)
            .build();

    assertThat(config.policy()).isEqualTo(policy);
  }

  @Test
  void build_whenProfileNameMissing_throwsNullPointerException() {
    assertThatThrownBy(
            () -> BootstrapConfig.builder().target(TARGET).addModule(SAMPLE_MODULE).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void build_whenTargetMissing_throwsNullPointerException() {
    assertThatThrownBy(
            () -> BootstrapConfig.builder().profileName(PROFILE).addModule(SAMPLE_MODULE).build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void build_whenNoModules_throwsIllegalStateException() {
    assertThatThrownBy(() -> BootstrapConfig.builder().profileName(PROFILE).target(TARGET).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("module");
  }

  @Test
  void build_whenDuplicateModuleNames_throwsIllegalStateException() {
    assertThatThrownBy(
            () ->
                BootstrapConfig.builder()
                    .profileName(PROFILE)
                    .target(TARGET)
                    .addModule(SAMPLE_MODULE)
                    .addModule(SAMPLE_MODULE)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void build_whenDuplicateModuleNamesAcrossPhases_throwsIllegalStateException() {
    var first =
        new Phase(
            new PhaseName("first"),
            "",
            List.of(SAMPLE_MODULE),
            List.of(),
            new RestartPolicy.None());
    var second =
        new Phase(
            new PhaseName("second"),
            "",
            List.of(SAMPLE_MODULE),
            List.of(),
            new RestartPolicy.None());

    assertThatThrownBy(
            () ->
                BootstrapConfig.builder()
                    .profileName(PROFILE)
                    .target(TARGET)
                    .addPhase(first)
                    .addPhase(second)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate module name");
  }

  @Test
  void modules_returnsUnmodifiableList() {
    var config =
        BootstrapConfig.builder()
            .profileName(PROFILE)
            .target(TARGET)
            .addModule(SAMPLE_MODULE)
            .build();

    assertThatThrownBy(() -> config.modules().add(SAMPLE_MODULE))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
