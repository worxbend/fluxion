package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.StateEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatusReportBuilderTest {

  private final StatusReportBuilder builder = new StatusReportBuilder();

  @Test
  void build_whenLiveVersionDiffersFromState_reportsVersionDrift() {
    var plan = plan(ModuleItem.packageItem(new ModuleName("tools"), "git", PackageManagerKind.DNF));
    var state =
        BootstrapState.empty("test", "1.0.0")
            .withEntry(stateEntry("git", ItemType.PACKAGE, Optional.of("1.0")));

    StatusReport report =
        builder.build(
            plan,
            Optional.of(state),
            Map.of("tools/git", new InstallationStatus.InstalledByProbe("git", "2.0")));

    assertThat(report.items())
        .extracting(StatusReport.Item::classification)
        .containsExactly(StatusReport.Classification.VERSION_DRIFT);
    assertThat(report.summary().versionDrift()).isEqualTo(1);
  }

  @Test
  void build_whenStateEntryIsNotConfigured_reportsStateOnly() {
    var plan = plan(ModuleItem.packageItem(new ModuleName("tools"), "git", PackageManagerKind.DNF));
    var state =
        BootstrapState.empty("test", "1.0.0")
            .withEntry(stateEntry("curl", ItemType.PACKAGE, Optional.empty()));

    StatusReport report =
        builder.build(
            plan,
            Optional.of(state),
            Map.of("tools/git", new InstallationStatus.NotInstalled("git")));

    assertThat(report.items())
        .extracting(StatusReport.Item::classification)
        .containsExactly(
            StatusReport.Classification.CONFIGURED_MISSING, StatusReport.Classification.STATE_ONLY);
    assertThat(report.summary().stateOnly()).isEqualTo(1);
  }

  @Test
  void build_whenProbeUnknown_reportsUnknown() {
    var plan = plan(ModuleItem.packageItem(new ModuleName("tools"), "git", PackageManagerKind.DNF));

    StatusReport report =
        builder.build(
            plan,
            Optional.empty(),
            Map.of("tools/git", new InstallationStatus.Unknown("git", "rpm missing")));

    assertThat(report.items().getFirst().classification())
        .isEqualTo(StatusReport.Classification.UNKNOWN);
    assertThat(report.summary().unknown()).isEqualTo(1);
  }

  @Test
  void build_sameBareKeyInDifferentModules_usesQualifiedLiveIdentity() {
    ModuleItem base = ModuleItem.packageItem(new ModuleName("base"), "git", PackageManagerKind.DNF);
    ModuleItem dev =
        ModuleItem.packageItem(new ModuleName("development"), "git", PackageManagerKind.DNF);
    var plan =
        new ExecutionPlan(
            "test",
            List.of(
                new ExecutionPlan.Phase(
                    "base",
                    List.of(),
                    ExecutionPlan.RestartEffect.NONE,
                    List.of(module(base), module(dev)))));

    StatusReport report =
        builder.build(
            plan,
            Optional.empty(),
            Map.of(
                "base/git",
                new InstallationStatus.InstalledByProbe("git", null),
                "development/git",
                new InstallationStatus.NotInstalled("git")));

    assertThat(report.items())
        .extracting(StatusReport.Item::classification)
        .containsExactly(
            StatusReport.Classification.CONFIGURED_INSTALLED,
            StatusReport.Classification.CONFIGURED_MISSING);
  }

  @Test
  void build_sourceSetupItems_areIncluded() {
    ModuleItem source =
        new ModuleItem(new ModuleName("repo"), "/etc/example.repo", ItemType.RPM_REPOSITORY);
    var plan =
        new ExecutionPlan(
            "test",
            List.of(
                new ExecutionPlan.Module(
                    "repo",
                    "source-setup",
                    List.of(new ExecutionPlan.Item(source, Optional.empty())))),
            List.of(),
            List.of());

    StatusReport report =
        builder.build(
            plan,
            Optional.empty(),
            Map.of("repo//etc/example.repo", new InstallationStatus.NotInstalled(source.key())));

    assertThat(report.items()).singleElement();
    assertThat(report.items().getFirst().key()).isEqualTo("/etc/example.repo");
  }

  private ExecutionPlan plan(ModuleItem item) {
    return new ExecutionPlan(
        "test",
        List.of(
            new ExecutionPlan.Phase(
                "base",
                List.of(),
                ExecutionPlan.RestartEffect.NONE,
                List.of(
                    new ExecutionPlan.Module(
                        item.moduleName().value(),
                        "packages",
                        List.of(new ExecutionPlan.Item(item, Optional.empty())))))));
  }

  private ExecutionPlan.Module module(ModuleItem item) {
    return new ExecutionPlan.Module(
        item.moduleName().value(),
        "packages",
        List.of(new ExecutionPlan.Item(item, Optional.empty())));
  }

  private StateEntry stateEntry(String key, ItemType type, Optional<String> version) {
    return new StateEntry("test", "tools", key, type, Instant.now(), version, Optional.empty());
  }
}
