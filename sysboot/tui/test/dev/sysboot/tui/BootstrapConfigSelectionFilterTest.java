package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitConfigScope;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GitRepoUpdate;
import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ScriptPath;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SystemdScope;
import dev.sysboot.core.SystemdState;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackageBackend;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.UserGroupsModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BootstrapConfigSelectionFilterTest {

  @Test
  void apply_whenAllSelected_preservesSourcesAndEveryModuleEffectField() {
    BootstrapConfig config = config();

    BootstrapConfig filtered =
        new BootstrapConfigSelectionFilter()
            .apply(config, BootstrapSelection.allSelected(config.phases()));

    assertThat(filtered.sourceSetups()).containsExactlyElementsOf(config.sourceSetups());
    assertThat(filtered.phases()).hasSize(1);
    assertThat(filtered.phases().getFirst().modules())
        .containsExactlyElementsOf(config.phases().getFirst().modules());
  }

  @Test
  void apply_whenIndividualEntriesDeselected_filtersEveryMultiEntryModuleKind() {
    BootstrapConfig config = config();
    BootstrapSelection selection = BootstrapSelection.allSelected(config.phases());
    List<BootstrapModule> modules =
        config.modules().stream().filter(isExplicitlyFilterable()).toList();
    modules.forEach(
        module -> selection.toggleEntry(module, SelectionEntryCatalog.entries(module).get(1)));

    BootstrapConfig filtered = new BootstrapConfigSelectionFilter().apply(config, selection);
    Map<String, BootstrapModule> selectedByName =
        filtered.modules().stream()
            .collect(Collectors.toMap(module -> module.name().value(), module -> module));

    for (BootstrapModule original : modules) {
      assertThat(SelectionEntryCatalog.entries(selectedByName.get(original.name().value())))
          .containsExactly(SelectionEntryCatalog.entries(original).getFirst());
    }
  }

  private Predicate<BootstrapModule> isExplicitlyFilterable() {
    return module ->
        module instanceof ShellScriptModule
            || module instanceof UserGroupsModule
            || module instanceof GitConfigModule
            || module instanceof GitRepoModule
            || module instanceof SystemdUnitModule
            || module instanceof GpgKeyModule
            || module instanceof ToolPackagesModule;
  }

  private BootstrapConfig config() {
    List<BootstrapModule> modules =
        List.of(
            packageModule(),
            new FlatpakModule(
                new ModuleName("flatpaks"),
                "flathub",
                List.of("org.mozilla.firefox", "org.gnome.Calculator"),
                true),
            shellScripts(),
            userGroups(),
            gitConfig(),
            gitRepos(),
            systemdUnits(),
            gpgKeys(),
            toolPackages());
    return BootstrapConfig.builder()
        .profileName(new ProfileName("selection-round-trip"))
        .target(new OsTarget.FedoraTarget("44"))
        .sourceSetups(List.of(sourceSetup()))
        .addPhase(
            new Phase(
                new PhaseName("base"), "Base", modules, List.of(), new RestartPolicy.None(), true))
        .build();
  }

  private PackageModule packageModule() {
    return new PackageModule(
        new ModuleName("packages"),
        PackageManagerKind.DNF,
        List.of(new PackageName("git"), new PackageName("curl")),
        List.of(new PackageManagerAction("upgrade", List.of("--refresh"))),
        true);
  }

  private FlatpakRemoteSourceSetup sourceSetup() {
    return new FlatpakRemoteSourceSetup(
        new ModuleName("flathub-source"),
        "flathub",
        URI.create("https://example.test/flathub.flatpakrepo"),
        true,
        Optional.of(new Sha256Digest("a".repeat(64))));
  }

  private ShellScriptModule shellScripts() {
    return new ShellScriptModule(
        new ModuleName("scripts"),
        List.of(script("first"), script("second")),
        Optional.of(Path.of("/tmp")),
        true,
        Optional.of("test -f /tmp/scripts-ready"));
  }

  private ShellScriptItem script(String name) {
    return new ShellScriptItem(
        name,
        Optional.of(new ScriptPath(Path.of("/tmp/" + name + ".sh"))),
        Optional.empty(),
        List.of("--flag"),
        Optional.of(Path.of("/tmp")),
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        java.time.Duration.ofMinutes(1),
        Optional.empty());
  }

  private UserGroupsModule userGroups() {
    return new UserGroupsModule(
        new ModuleName("groups"),
        Optional.of("developer"),
        List.of("docker", "libvirt"),
        true,
        false,
        Optional.of("Start a new session."),
        true);
  }

  private GitConfigModule gitConfig() {
    return new GitConfigModule(
        new ModuleName("git-config"),
        GitConfigScope.SYSTEM,
        Map.of("user.name", "Developer", "user.email", "dev@example.test"),
        true);
  }

  private GitRepoModule gitRepos() {
    return new GitRepoModule(
        new ModuleName("git-repos"), List.of(repo("first", "a"), repo("second", "b")), true);
  }

  private GitRepoModule.GitRepo repo(String destination, String revision) {
    return new GitRepoModule.GitRepo(
        "https://example.test/" + destination + ".git",
        "/tmp/" + destination,
        Optional.of(revision.repeat(40)),
        Optional.of(1),
        true,
        GitRepoUpdate.NONE);
  }

  private SystemdUnitModule systemdUnits() {
    return new SystemdUnitModule(
        new ModuleName("systemd"),
        SystemdScope.SYSTEM,
        List.of(
            new SystemdUnitModule.SystemdUnit("docker", true, SystemdState.STARTED, false),
            new SystemdUnitModule.SystemdUnit("cups", false, SystemdState.STOPPED, false)),
        true);
  }

  private GpgKeyModule gpgKeys() {
    return new GpgKeyModule(
        new ModuleName("keys"),
        List.of(
            new GpgKeyModule.GpgKey(
                "file:///tmp/first.asc",
                Optional.of(Path.of("/etc/apt/keyrings/first.gpg")),
                "a".repeat(40)),
            new GpgKeyModule.GpgKey(
                "file:///tmp/second.asc",
                Optional.of(Path.of("/etc/apt/keyrings/second.gpg")),
                "b".repeat(40))),
        true);
  }

  private ToolPackagesModule toolPackages() {
    return new ToolPackagesModule(
        new ModuleName("tools"),
        ToolPackageBackend.PIPX,
        List.of(
            new ToolPackagesModule.ToolPackage("black"),
            new ToolPackagesModule.ToolPackage("ruff")),
        true);
  }
}
