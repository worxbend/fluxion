package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.PackageManagerKind;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that enumerates the manifest plan kinds.
 *
 * <p>Before this table the same 25 kind names were spelled out in five string sets in {@link
 * WorkstationProfileValidator} and again in a switch in {@link WorkstationProfileConfigMapper}, so
 * a kind that was added to one and forgotten in another either validated and never ran or ran
 * without ever being checked. A row here carries everything the frontend needs to know about a
 * kind: how the shared shape checks treat it, which package-manager actions it accepts, the check
 * for its own spec, and the mapper that turns it into a module.
 */
final class PlanKinds {

  /** How the kind-independent part of validation treats a plan entry's {@code spec}. */
  enum Category {
    /** {@code spec.packages} must be non-empty; {@code spec.actions} are checked. */
    PACKAGES,
    /** {@code spec.apps} or {@code spec.appIds} must be non-empty. */
    APPS,
    /** {@code spec.packages} holds SDKMAN candidate strings or objects. */
    SDKMAN,
    /** {@code spec} is mandatory; everything else is up to the kind's own check. */
    INSTALLER,
    /** No shared shape at all. */
    CONTROL
  }

  /** A kind's own spec validation, bound to the validator instance that owns the shared helpers. */
  @FunctionalInterface
  interface SpecCheck {
    void check(
        WorkstationProfileValidator validator,
        String path,
        String entryName,
        PlanSpecDocument spec,
        List<String> errors);
  }

  /** A kind's mapping to a module, bound to the mapper instance that owns the shared helpers. */
  @FunctionalInterface
  interface ModuleMapper {
    Optional<BootstrapModule> map(
        WorkstationProfileConfigMapper mapper, PlanEntryDocument entry, BootstrapPolicy policy);
  }

  record PlanKind(
      String id,
      String summary,
      Category category,
      Set<String> packageActions,
      SpecCheck specCheck,
      ModuleMapper mapper) {}

  private static final SpecCheck NO_SPEC_CHECK = (validator, path, name, spec, errors) -> {};

  private static final List<PlanKind> KINDS =
      List.of(
          packages(
              "apt-packages",
              "Install packages with apt.",
              Set.of("update", "upgrade", "dist-upgrade"),
              PackageManagerKind.APT),
          new PlanKind(
              "aur-packages",
              "Install AUR packages with paru or yay.",
              Category.PACKAGES,
              Set.of(),
              (validator, path, name, spec, errors) ->
                  validator.validateAurPackageManager(path, spec, errors),
              (mapper, entry, policy) ->
                  Optional.of(
                      mapper.packageModule(entry, mapper.aurPackageManager(entry), policy))),
          packages(
              "cargo-packages", "Install crates with cargo.", Set.of(), PackageManagerKind.CARGO),
          packages(
              "dnf-packages",
              "Install packages with dnf.",
              Set.of("check-update", "upgrade", "swap", "groupupdate", "group-update"),
              PackageManagerKind.DNF),
          packages(
              "pacman-packages",
              "Install packages with pacman.",
              Set.of("sync-upgrade", "syu", "upgrade"),
              PackageManagerKind.PACMAN),
          packages(
              "zypper-packages",
              "Install packages with zypper.",
              Set.of("refresh", "update", "dup", "dup-from"),
              PackageManagerKind.ZYPPER),
          new PlanKind(
              "sdkman-packages",
              "Install SDKMAN candidates such as java or maven.",
              Category.SDKMAN,
              Set.of(),
              NO_SPEC_CHECK,
              (mapper, entry, policy) -> Optional.of(mapper.sdkmanModule(entry, policy))),
          new PlanKind(
              "flatpak-packages",
              "Install Flatpak applications.",
              Category.APPS,
              Set.of(),
              NO_SPEC_CHECK,
              (mapper, entry, policy) -> Optional.of(mapper.flatpakModule(entry, policy))),
          installer(
              "binary-downloads",
              "Download and install a compiled binary or archive.",
              WorkstationProfileValidator::validateBinarySpec,
              (mapper, entry, policy) -> Optional.of(mapper.compiledBinaryModule(entry, policy))),
          installer(
              "shell-scripts",
              "Run local or HTTPS-fetched shell scripts.",
              WorkstationProfileValidator::validateScriptSpec,
              (mapper, entry, policy) -> Optional.of(mapper.shellScriptModule(entry, policy))),
          installer(
              "commands",
              "Run shell or direct argv commands.",
              (validator, path, name, spec, errors) ->
                  validator.validateCommandSpec(path, spec, errors),
              (mapper, entry, policy) -> Optional.of(mapper.shellCommandModule(entry, policy))),
          installer(
              "file-writes",
              "Write files from inline content or a source path.",
              WorkstationProfileValidator::validateFileWriteSpec,
              WorkstationProfileConfigMapper::fileWriteModule),
          installer(
              "nerd-fonts",
              "Install Nerd Font families via nerd-fonts-installer.",
              WorkstationProfileValidator::validateNerdFontSpec,
              (mapper, entry, policy) -> Optional.of(mapper.nerdFontModule(entry))),
          installer(
              "dotfiles-apply",
              "Apply a Dotbot configuration via dotbot.",
              WorkstationProfileValidator::validateDotfilesSpec,
              (mapper, entry, policy) -> Optional.of(mapper.dotbotModule(entry))),
          installer(
              "binstaller-profile",
              "Install binaries from a binstaller BinaryDistributionProfile.",
              WorkstationProfileValidator::validateBinstallerSpec,
              (mapper, entry, policy) -> Optional.of(mapper.binstallerModule(entry, policy))),
          installer(
              "user-groups",
              "Add the user to groups. Append-only; never removes membership.",
              WorkstationProfileValidator::validateUserGroupsSpec,
              (mapper, entry, policy) -> Optional.of(mapper.userGroupsModule(entry, policy))),
          installer(
              "git-config",
              "Set git config entries at global, system or local scope.",
              WorkstationProfileValidator::validateGitConfigSpec,
              (mapper, entry, policy) -> Optional.of(mapper.gitConfigModule(entry, policy))),
          installer(
              "git-repo",
              "Clone git repositories, and optionally update existing clones.",
              WorkstationProfileValidator::validateGitRepoSpec,
              (mapper, entry, policy) -> Optional.of(mapper.gitRepoModule(entry, policy))),
          installer(
              "systemd-unit",
              "Enable, mask, start or stop systemd units.",
              WorkstationProfileValidator::validateSystemdUnitSpec,
              (mapper, entry, policy) -> Optional.of(mapper.systemdUnitModule(entry, policy))),
          installer(
              "system-setting",
              "Set timezone, hostname, locale, NTP and the RTC mode.",
              WorkstationProfileValidator::validateSystemSettingSpec,
              (mapper, entry, policy) -> Optional.of(mapper.systemSettingModule(entry, policy))),
          installer(
              "system-update",
              "Refresh package metadata or upgrade every installed package.",
              WorkstationProfileValidator::validateSystemUpdateSpec,
              (mapper, entry, policy) -> Optional.of(mapper.systemUpdateModule(entry, policy))),
          installer(
              "gpg-key",
              "Import repository signing keys into a keyring.",
              WorkstationProfileValidator::validateGpgKeySpec,
              (mapper, entry, policy) -> Optional.of(mapper.gpgKeyModule(entry, policy))),
          installer(
              "tool-packages",
              "Install via a language tool: cargo-binstall, pipx, snap, uv, npm, go.",
              WorkstationProfileValidator::validateToolPackagesSpec,
              (mapper, entry, policy) -> Optional.of(mapper.toolPackagesModule(entry, policy))),
          installer(
              "zypper-repository",
              "Add an openSUSE repository, with its signing key.",
              WorkstationProfileValidator::validateZypperRepositorySpec,
              (mapper, entry, policy) -> Optional.of(mapper.zypperRepositoryModule(entry))),
          new PlanKind(
              "interrupt",
              "Write a resumable checkpoint and stop cleanly.",
              Category.CONTROL,
              Set.of(),
              (validator, path, name, spec, errors) ->
                  validator.validateInterruptSpec(path, spec, errors),
              (mapper, entry, policy) -> Optional.of(mapper.interruptModule(entry))));

  private static final Map<String, PlanKind> BY_ID = index(KINDS);

  private PlanKinds() {}

  /** Looks a kind up by its already-normalised (stripped, lower-case) manifest id. */
  static Optional<PlanKind> find(String normalizedKind) {
    return Optional.ofNullable(BY_ID.get(normalizedKind));
  }

  /** Every supported kind id, in declaration order. */
  static List<String> ids() {
    return List.copyOf(BY_ID.keySet());
  }

  /**
   * The supported kind a misspelling most likely meant, if one is close enough to be worth naming.
   *
   * <p>The threshold scales with the length of what the user typed rather than being a fixed edit
   * distance, so {@code apt-package} suggests {@code apt-packages} while {@code helm} — which is
   * not a kind at all — suggests nothing instead of pointing at whichever id happens to sort first.
   */
  static Optional<String> closestId(String rawKind) {
    String candidate = rawKind == null ? "" : rawKind.strip().toLowerCase(Locale.ROOT);
    if (candidate.isEmpty()) {
      return Optional.empty();
    }
    int budget = Math.max(2, candidate.length() / 3);
    return BY_ID.keySet().stream()
        .map(id -> Map.entry(id, editDistance(candidate, id)))
        .filter(scored -> scored.getValue() <= budget)
        .min(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey);
  }

  /** Plain Levenshtein distance over two rows, which is all a suggestion needs. */
  private static int editDistance(String left, String right) {
    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];
    for (int column = 0; column <= right.length(); column++) {
      previous[column] = column;
    }
    for (int row = 1; row <= left.length(); row++) {
      current[0] = row;
      for (int column = 1; column <= right.length(); column++) {
        int substitution =
            previous[column - 1] + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
        current[column] =
            Math.min(substitution, 1 + Math.min(previous[column], current[column - 1]));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[right.length()];
  }

  private static PlanKind packages(
      String id, String summary, Set<String> actions, PackageManagerKind manager) {
    return new PlanKind(
        id,
        summary,
        Category.PACKAGES,
        actions,
        NO_SPEC_CHECK,
        (mapper, entry, policy) -> Optional.of(mapper.packageModule(entry, manager, policy)));
  }

  private static PlanKind installer(
      String id, String summary, SpecCheck specCheck, ModuleMapper mapper) {
    return new PlanKind(id, summary, Category.INSTALLER, Set.of(), specCheck, mapper);
  }

  private static Map<String, PlanKind> index(List<PlanKind> kinds) {
    var byId = new LinkedHashMap<String, PlanKind>();
    for (PlanKind kind : kinds) {
      PlanKind previous = byId.put(kind.id(), kind);
      if (previous != null) {
        throw new IllegalStateException("Duplicate plan kind id: " + kind.id());
      }
    }
    return Collections.unmodifiableMap(byId);
  }
}
