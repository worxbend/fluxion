package dev.sysboot.config;

import java.util.List;
import java.util.Optional;

/**
 * The public, read-only view of the manifest plan kinds Fluxion supports.
 *
 * <p>This exists so the CLI can answer "what can I write in a profile?" from the same table the
 * validator and the mapper use, rather than from a list a command keeps in sync by hand. The
 * registry itself stays package-private because its rows carry behaviour, not just description.
 */
public final class PlanKindCatalog {

  /** One supported kind, as a user needs to see it. */
  public record Entry(String id, String summary, String category, List<String> packageActions) {

    public Entry {
      packageActions = List.copyOf(packageActions);
    }
  }

  private PlanKindCatalog() {}

  /** Every supported kind, in the order the reference documentation presents them. */
  public static List<Entry> entries() {
    return PlanKinds.ids().stream()
        .map(id -> PlanKinds.find(id).orElseThrow())
        .map(
            kind ->
                new Entry(
                    kind.id(),
                    kind.summary(),
                    kind.category().name().toLowerCase(java.util.Locale.ROOT),
                    kind.packageActions().stream().sorted().toList()))
        .toList();
  }

  /** The kind a misspelling most likely meant, if one is close enough to be worth naming. */
  public static Optional<String> suggest(String rawKind) {
    return PlanKinds.closestId(rawKind);
  }
}
