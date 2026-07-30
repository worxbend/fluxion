package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.PackageManagerKind;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
      Category category,
      Set<String> packageActions,
      SpecCheck specCheck,
      ModuleMapper mapper) {}

  private static final SpecCheck NO_SPEC_CHECK = (validator, path, name, spec, errors) -> {};

  private static final List<PlanKind> KINDS =
      List.of(
          packages(
              "apt-packages", Set.of("update", "upgrade", "dist-upgrade"), PackageManagerKind.APT),
          new PlanKind(
              "aur-packages",
              Category.PACKAGES,
              Set.of(),
              (validator, path, name, spec, errors) ->
                  validator.validateAurPackageManager(path, spec, errors),
              (mapper, entry, policy) ->
                  Optional.of(
                      mapper.packageModule(entry, mapper.aurPackageManager(entry), policy))),
          packages("cargo-packages", Set.of(), PackageManagerKind.CARGO),
          packages(
              "dnf-packages",
              Set.of("check-update", "upgrade", "swap", "groupupdate", "group-update"),
              PackageManagerKind.DNF),
          packages(
              "pacman-packages",
              Set.of("sync-upgrade", "syu", "upgrade"),
              PackageManagerKind.PACMAN),
          packages(
              "zypper-packages",
              Set.of("refresh", "update", "dup", "dup-from"),
              PackageManagerKind.ZYPPER),
          new PlanKind(
              "sdkman-packages",
              Category.SDKMAN,
              Set.of(),
              NO_SPEC_CHECK,
              (mapper, entry, policy) -> Optional.of(mapper.sdkmanModule(entry, policy))),
          new PlanKind(
              "flatpak-packages",
              Category.APPS,
              Set.of(),
              NO_SPEC_CHECK,
              (mapper, entry, policy) -> Optional.of(mapper.flatpakModule(entry, policy))),
          installer(
              "binary-downloads",
              WorkstationProfileValidator::validateBinarySpec,
              (mapper, entry, policy) -> Optional.of(mapper.compiledBinaryModule(entry, policy))),
          installer(
              "shell-scripts",
              WorkstationProfileValidator::validateScriptSpec,
              (mapper, entry, policy) -> Optional.of(mapper.shellScriptModule(entry, policy))),
          installer(
              "commands",
              (validator, path, name, spec, errors) ->
                  validator.validateCommandSpec(path, spec, errors),
              (mapper, entry, policy) -> Optional.of(mapper.shellCommandModule(entry, policy))),
          installer(
              "file-writes",
              WorkstationProfileValidator::validateFileWriteSpec,
              WorkstationProfileConfigMapper::fileWriteModule),
          installer(
              "nerd-fonts",
              WorkstationProfileValidator::validateNerdFontSpec,
              (mapper, entry, policy) -> Optional.of(mapper.nerdFontModule(entry))),
          installer(
              "dotfiles-apply",
              WorkstationProfileValidator::validateDotfilesSpec,
              (mapper, entry, policy) -> Optional.of(mapper.dotbotModule(entry))),
          installer(
              "binstaller-profile",
              WorkstationProfileValidator::validateBinstallerSpec,
              (mapper, entry, policy) -> Optional.of(mapper.binstallerModule(entry, policy))),
          installer(
              "user-groups",
              WorkstationProfileValidator::validateUserGroupsSpec,
              (mapper, entry, policy) -> Optional.of(mapper.userGroupsModule(entry, policy))),
          installer(
              "git-config",
              WorkstationProfileValidator::validateGitConfigSpec,
              (mapper, entry, policy) -> Optional.of(mapper.gitConfigModule(entry, policy))),
          installer(
              "git-repo",
              WorkstationProfileValidator::validateGitRepoSpec,
              (mapper, entry, policy) -> Optional.of(mapper.gitRepoModule(entry, policy))),
          installer(
              "systemd-unit",
              WorkstationProfileValidator::validateSystemdUnitSpec,
              (mapper, entry, policy) -> Optional.of(mapper.systemdUnitModule(entry, policy))),
          installer(
              "system-setting",
              WorkstationProfileValidator::validateSystemSettingSpec,
              (mapper, entry, policy) -> Optional.of(mapper.systemSettingModule(entry, policy))),
          installer(
              "system-update",
              WorkstationProfileValidator::validateSystemUpdateSpec,
              (mapper, entry, policy) -> Optional.of(mapper.systemUpdateModule(entry, policy))),
          installer(
              "gpg-key",
              WorkstationProfileValidator::validateGpgKeySpec,
              (mapper, entry, policy) -> Optional.of(mapper.gpgKeyModule(entry, policy))),
          installer(
              "tool-packages",
              WorkstationProfileValidator::validateToolPackagesSpec,
              (mapper, entry, policy) -> Optional.of(mapper.toolPackagesModule(entry, policy))),
          installer(
              "zypper-repository",
              WorkstationProfileValidator::validateZypperRepositorySpec,
              (mapper, entry, policy) -> Optional.of(mapper.zypperRepositoryModule(entry))),
          new PlanKind(
              "interrupt",
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

  private static PlanKind packages(String id, Set<String> actions, PackageManagerKind manager) {
    return new PlanKind(
        id,
        Category.PACKAGES,
        actions,
        NO_SPEC_CHECK,
        (mapper, entry, policy) -> Optional.of(mapper.packageModule(entry, manager, policy)));
  }

  private static PlanKind installer(String id, SpecCheck specCheck, ModuleMapper mapper) {
    return new PlanKind(id, Category.INSTALLER, Set.of(), specCheck, mapper);
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
