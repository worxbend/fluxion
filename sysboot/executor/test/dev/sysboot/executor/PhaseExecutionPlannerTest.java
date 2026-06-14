package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.RestartPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PhaseExecutionPlannerTest {

  private PhaseExecutionPlanner planner;

  @BeforeEach
  void setUp() {
    planner = new PhaseExecutionPlanner();
  }

  private Phase phase(String name, String... deps) {
    List<PhaseName> depList = List.of(deps).stream().map(PhaseName::new).toList();
    return new Phase(new PhaseName(name), "", List.of(), depList, new RestartPolicy.None());
  }

  @Test
  void plan_emptyList_returnsEmpty() {
    assertThat(planner.plan(List.of())).isEmpty();
  }

  @Test
  void plan_singlePhase_returnsSingle() {
    var phases = List.of(phase("a"));
    assertThat(planner.plan(phases)).extracting(p -> p.name().value()).containsExactly("a");
  }

  @Test
  void plan_linearDependency_returnsCorrectOrder() {
    // b depends on a; a must come first
    var phases = List.of(phase("b", "a"), phase("a"));
    var result = planner.plan(phases);
    assertThat(result).extracting(p -> p.name().value()).containsExactly("a", "b");
  }

  @Test
  void plan_diamondDependency_respectsBothDeps() {
    // c and d both depend on a; e depends on c and d
    var phases = List.of(phase("e", "c", "d"), phase("c", "a"), phase("d", "a"), phase("a"));
    var result = planner.plan(phases);
    int aIdx = indexOf(result, "a");
    int cIdx = indexOf(result, "c");
    int dIdx = indexOf(result, "d");
    int eIdx = indexOf(result, "e");
    assertThat(aIdx).isLessThan(cIdx);
    assertThat(aIdx).isLessThan(dIdx);
    assertThat(cIdx).isLessThan(eIdx);
    assertThat(dIdx).isLessThan(eIdx);
  }

  @Test
  void plan_cycleDetected_throwsCyclicDependencyException() {
    // a → b → a
    var phases = List.of(phase("a", "b"), phase("b", "a"));
    assertThatThrownBy(() -> planner.plan(phases))
        .isInstanceOf(CyclicDependencyException.class)
        .hasMessageContaining("Circular");
  }

  @Test
  void plan_unknownDependency_throwsIllegalArgument() {
    var phases = List.of(phase("a", "nonexistent"));
    assertThatThrownBy(() -> planner.plan(phases))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonexistent");
  }

  @Test
  void computeBlocked_failedPhaseBlocksDependents() {
    var phases = List.of(phase("a"), phase("b", "a"), phase("c", "b"), phase("d"));
    Set<PhaseName> failed = Set.of(new PhaseName("a"));
    Set<PhaseName> blocked = planner.computeBlocked(phases, failed);
    assertThat(blocked).extracting(PhaseName::value).containsExactlyInAnyOrder("b", "c");
    assertThat(blocked).doesNotContain(new PhaseName("d"));
  }

  @Test
  void computeBlocked_independentPhaseNotBlocked() {
    var phases = List.of(phase("a"), phase("b", "a"), phase("c"));
    Set<PhaseName> failed = Set.of(new PhaseName("a"));
    Set<PhaseName> blocked = planner.computeBlocked(phases, failed);
    assertThat(blocked).extracting(PhaseName::value).containsOnly("b");
  }

  private int indexOf(List<Phase> phases, String name) {
    for (int i = 0; i < phases.size(); i++) {
      if (phases.get(i).name().value().equals(name)) return i;
    }
    return -1;
  }
}
