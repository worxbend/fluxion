package dev.sysboot.executor;

import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PhaseExecutionPlanner {

  /**
   * Topological sort via Kahn's algorithm. Throws if any phase names in {@code dependsOn} are
   * undefined or a cycle exists.
   */
  public List<Phase> plan(List<Phase> phases) {
    Map<String, Phase> byName = new HashMap<>();
    for (Phase p : phases) {
      byName.put(p.name().value(), p);
    }

    validateAllDepsExist(phases, byName);

    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, List<String>> successors = new HashMap<>();

    for (Phase p : phases) {
      inDegree.putIfAbsent(p.name().value(), 0);
      successors.putIfAbsent(p.name().value(), new ArrayList<>());
    }
    for (Phase p : phases) {
      for (PhaseName dep : p.dependsOn()) {
        successors.get(dep.value()).add(p.name().value());
        inDegree.merge(p.name().value(), 1, Integer::sum);
      }
    }

    Deque<String> ready = new ArrayDeque<>();
    for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
      if (e.getValue() == 0) ready.add(e.getKey());
    }

    List<Phase> sorted = new ArrayList<>();
    while (!ready.isEmpty()) {
      String current = ready.poll();
      sorted.add(byName.get(current));
      for (String successor : successors.get(current)) {
        int newDegree = inDegree.merge(successor, -1, Integer::sum);
        if (newDegree == 0) ready.add(successor);
      }
    }

    if (sorted.size() != phases.size()) {
      Set<String> cycle =
          phases.stream()
              .map(p -> p.name().value())
              .filter(n -> inDegree.get(n) > 0)
              .collect(Collectors.toUnmodifiableSet());
      throw new CyclicDependencyException("Circular dependency detected among phases: " + cycle);
    }

    return List.copyOf(sorted);
  }

  /** BFS to compute all phases blocked because their dependency is in {@code failedPhases}. */
  public Set<PhaseName> computeBlocked(List<Phase> allPhases, Set<PhaseName> failedPhases) {
    Map<String, List<String>> dependents = new HashMap<>();
    for (Phase p : allPhases) {
      dependents.putIfAbsent(p.name().value(), new ArrayList<>());
      for (PhaseName dep : p.dependsOn()) {
        dependents.computeIfAbsent(dep.value(), k -> new ArrayList<>()).add(p.name().value());
      }
    }

    Set<PhaseName> blocked = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    failedPhases.forEach(pn -> queue.add(pn.value()));

    while (!queue.isEmpty()) {
      String current = queue.poll();
      List<String> children = dependents.getOrDefault(current, List.of());
      for (String child : children) {
        PhaseName childName = new PhaseName(child);
        if (!failedPhases.contains(childName) && blocked.add(childName)) {
          queue.add(child);
        }
      }
    }

    return Set.copyOf(blocked);
  }

  private void validateAllDepsExist(List<Phase> phases, Map<String, Phase> byName) {
    for (Phase p : phases) {
      for (PhaseName dep : p.dependsOn()) {
        if (!byName.containsKey(dep.value())) {
          throw new IllegalArgumentException(
              "Phase '%s' declares dependency on unknown phase '%s'"
                  .formatted(p.name().value(), dep.value()));
        }
      }
    }
  }
}
