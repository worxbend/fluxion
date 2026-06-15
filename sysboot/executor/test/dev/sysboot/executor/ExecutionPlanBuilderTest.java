package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionPlanBuilderTest {

  @Test
  void build_packageModule_includesItemsAndCommandPreviews() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("base"),
            "",
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true)),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    assertThat(plan.profileName()).isEqualTo("test");
    assertThat(plan.phases()).hasSize(1);
    ExecutionPlan.Item item = plan.phases().get(0).modules().get(0).items().get(0);
    assertThat(item.item().key()).isEqualTo("git");
    assertThat(item.commandPreview()).contains(List.of("sudo", "dnf", "install", "-y", "git"));
  }

  @Test
  void build_restartPolicy_mapsPromptLogoutEffect() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var base =
        new Phase(
            new PhaseName("base"),
            "",
            List.of(
                new PackageModule(
                    new ModuleName("base-tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true)),
            List.of(),
            new RestartPolicy.None(),
            false);
    var shell =
        new Phase(
            new PhaseName("shell"),
            "",
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("zsh")),
                    true)),
            List.of(new PhaseName("base")),
            new RestartPolicy.PromptLogout("log out"),
            false);

    ExecutionPlan plan = builder.build(config(List.of(base, shell)));

    ExecutionPlan.Phase shellPhase =
        plan.phases().stream()
            .filter(phase -> phase.name().equals("shell"))
            .findFirst()
            .orElseThrow();

    assertThat(shellPhase.dependsOn()).containsExactly("base");
    assertThat(shellPhase.restartEffect()).isEqualTo(ExecutionPlan.RestartEffect.PROMPT_LOGOUT);
  }

  private static BootstrapConfig config(List<Phase> phases) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("44"));
    phases.forEach(builder::addPhase);
    return builder.build();
  }

  private static PackageManagerExecutor dnf() {
    return new PackageManagerExecutor() {
      @Override
      public boolean supports(PackageManagerKind kind) {
        return kind == PackageManagerKind.DNF;
      }

      @Override
      public List<String> installCommand(PackageName packageName) {
        return List.of("sudo", "dnf", "install", "-y", packageName.value());
      }

      @Override
      public StepResult install(PackageName packageName) {
        return new StepResult.Success(packageName.value(), Duration.ZERO);
      }
    };
  }
}
