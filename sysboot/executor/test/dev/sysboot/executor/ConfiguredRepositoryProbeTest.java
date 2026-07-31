package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PacmanRepositorySourceSetup;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfiguredRepositoryProbeTest {

  @Test
  void aptProbe_republishesRemoteTrustUntilInstalledKeyAttestationExists() {
    Path source = Path.of("/etc/apt/sources.list.d/example.list");
    Path keyring = Path.of("/etc/apt/keyrings/example.gpg");
    String entry = "deb [signed-by=" + keyring + "] https://packages.example.test stable main";
    var runner = new ContentShellRunner();
    runner.content(source, entry + "\n");
    runner.nonEmpty(keyring);
    var setup =
        new AptRepositorySourceSetup(
            new ModuleName("apt"),
            entry,
            source,
            Optional.empty(),
            Optional.of(keyring),
            Optional.empty());
    ModuleItem item = ModuleItem.sourceSetupItem(setup, source.toString(), ItemType.APT_REPOSITORY);
    var probe = new AptRepositoryProbe(runner);

    assertThat(probe.probe(item)).isInstanceOf(InstallationStatus.NotInstalled.class);
    runner.content(source, entry.replace("stable", "testing") + "\n");
    assertThat(probe.probe(item)).isInstanceOf(InstallationStatus.NotInstalled.class);
    runner.content(source, entry + "\n");
    runner.empty(keyring);
    assertThat(probe.probe(item)).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("iniDriftCases")
  void iniRepositoryProbes_rejectIdentityDestinationAndSettingDrift(
      String name, String content, boolean zypper) {
    Path repoFile =
        Path.of(
            zypper ? "/etc/zypp/repos.d/" + name + ".repo" : "/etc/yum.repos.d/" + name + ".repo");
    var runner = new ContentShellRunner();
    runner.content(repoFile, content);
    ModuleName moduleName = new ModuleName(name);
    ModuleItem item;
    InstalledProbeAdapter probe;
    if (zypper) {
      var setup =
          new ZypperRepositorySourceSetup(
              moduleName,
              "example",
              URI.create("https://packages.example.test/repo"),
              repoFile,
              Optional.empty(),
              false,
              false,
              Optional.empty());
      item = ModuleItem.sourceSetupItem(setup, repoFile.toString(), ItemType.ZYPPER_REPOSITORY);
      probe = value -> new ZypperRepositoryProbe(runner).probe(value);
    } else {
      var setup =
          new RpmRepositorySourceSetup(
              moduleName,
              "example",
              URI.create("https://packages.example.test/repo"),
              repoFile,
              Optional.empty(),
              false,
              false,
              Optional.empty());
      item = ModuleItem.sourceSetupItem(setup, repoFile.toString(), ItemType.RPM_REPOSITORY);
      probe = value -> new RpmRepositoryProbe(runner).probe(value);
    }

    assertThat(probe.probe(item)).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  static Stream<Arguments> iniDriftCases() {
    String header = "[example]\nname=example\n";
    return Stream.of(
        Arguments.of(
            "rpm-base-url",
            header + "baseurl=https://evil.example/repo\nenabled=1\ngpgcheck=0\n",
            false),
        Arguments.of(
            "rpm-enabled",
            header + "baseurl=https://packages.example.test/repo\nenabled=1\ngpgcheck=0\n",
            false),
        Arguments.of(
            "rpm-gpg-check",
            header + "baseurl=https://packages.example.test/repo\nenabled=1\ngpgcheck=1\n",
            false),
        Arguments.of(
            "zypper-identity",
            "[other]\nbaseurl=https://packages.example.test/repo\nenabled=1\ngpgcheck=0\n",
            true),
        Arguments.of(
            "zypper-base-url",
            header + "baseurl=https://evil.example/repo\nenabled=1\ngpgcheck=0\n",
            true));
  }

  @Test
  void iniRepositoryProbes_acceptExactConfiguredIdentityAndSettings() {
    String content =
        "[example]\n"
            + "name=example\n"
            + "baseurl=https://packages.example.test/repo\n"
            + "enabled=0\n"
            + "gpgcheck=0\n";
    String zypperContent = content + "autorefresh=1\n";
    Path rpmFile = Path.of("/etc/yum.repos.d/exact-rpm.repo");
    Path zypperFile = Path.of("/etc/zypp/repos.d/exact-zypper.repo");
    var runner = new ContentShellRunner();
    runner.content(rpmFile, content);
    runner.content(zypperFile, zypperContent);
    var rpm =
        new RpmRepositorySourceSetup(
            new ModuleName("rpm"),
            "example",
            URI.create("https://packages.example.test/repo"),
            rpmFile,
            Optional.empty(),
            false,
            false,
            Optional.empty());
    var zypper =
        new ZypperRepositorySourceSetup(
            new ModuleName("zypper"),
            "example",
            URI.create("https://packages.example.test/repo"),
            zypperFile,
            Optional.empty(),
            false,
            false,
            Optional.empty());

    assertThat(
            new RpmRepositoryProbe(runner)
                .probe(
                    ModuleItem.sourceSetupItem(rpm, rpmFile.toString(), ItemType.RPM_REPOSITORY)))
        .isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(
            new ZypperRepositoryProbe(runner)
                .probe(
                    ModuleItem.sourceSetupItem(
                        zypper, zypperFile.toString(), ItemType.ZYPPER_REPOSITORY)))
        .isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void zypperProbe_rejectsAutorefreshDrift() {
    Path path = Path.of("/etc/zypp/repos.d/autorefresh.repo");
    var runner = new ContentShellRunner();
    runner.content(
        path,
        """
        [example]
        name=example
        baseurl=https://packages.example.test/repo
        enabled=0
        gpgcheck=0
        autorefresh=0
        """);
    var setup =
        new ZypperRepositorySourceSetup(
            new ModuleName("zypper"),
            "example",
            URI.create("https://packages.example.test/repo"),
            path,
            Optional.empty(),
            false,
            false,
            true,
            Optional.empty());

    assertThat(
            new ZypperRepositoryProbe(runner)
                .probe(
                    ModuleItem.sourceSetupItem(setup, path.toString(), ItemType.ZYPPER_REPOSITORY)))
        .isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pacmanDriftCases")
  void pacmanProbe_rejectsRepositorySettingDrift(String name, String block) {
    Path config = Path.of("/etc/pacman.conf");
    var runner = new ContentShellRunner();
    runner.content(config, block);
    var setup =
        new PacmanRepositorySourceSetup(
            new ModuleName("pacman"),
            "example",
            URI.create("https://packages.example.test/$arch"),
            config,
            Optional.of("Required TrustedOnly"),
            Optional.of(Path.of("/etc/pacman.d/example")),
            true);
    ModuleItem item = ModuleItem.sourceSetupItem(setup, "example", ItemType.PACMAN_REPOSITORY);

    assertThat(new PacmanRepositoryProbe(runner).probe(item))
        .as(name)
        .isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  static Stream<Arguments> pacmanDriftCases() {
    return Stream.of(
        Arguments.of(
            "server",
            "[example]\nServer = https://evil.example/$arch\n"
                + "SigLevel = Required TrustedOnly\nInclude = /etc/pacman.d/example\n"),
        Arguments.of(
            "signature policy",
            "[example]\nServer = https://packages.example.test/$arch\n"
                + "SigLevel = Never\nInclude = /etc/pacman.d/example\n"),
        Arguments.of(
            "include",
            "[example]\nServer = https://packages.example.test/$arch\n"
                + "SigLevel = Required TrustedOnly\nInclude = /etc/pacman.d/other\n"),
        Arguments.of(
            "disabled settings",
            "[example]\n# Server = https://packages.example.test/$arch\n"
                + "# SigLevel = Required TrustedOnly\n# Include = /etc/pacman.d/example\n"));
  }

  @Test
  void pacmanProbe_acceptsExactConfiguredBlock() {
    Path config = Path.of("/etc/pacman.conf");
    var runner = new ContentShellRunner();
    runner.content(
        config,
        "[example]\n"
            + "Server = https://packages.example.test/$arch\n"
            + "SigLevel = Required TrustedOnly\n"
            + "Include = /etc/pacman.d/example\n");
    var setup =
        new PacmanRepositorySourceSetup(
            new ModuleName("pacman"),
            "example",
            URI.create("https://packages.example.test/$arch"),
            config,
            Optional.of("Required TrustedOnly"),
            Optional.of(Path.of("/etc/pacman.d/example")),
            true);

    assertThat(
            new PacmanRepositoryProbe(runner)
                .probe(ModuleItem.sourceSetupItem(setup, "example", ItemType.PACMAN_REPOSITORY)))
        .isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void flatpakProbe_requiresConfiguredScopeNameAndUrl() {
    ShellRunner runner = org.mockito.Mockito.mock(ShellRunner.class);
    when(runner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(0, "flathub https://different.example/repo\n", "", Duration.ZERO));
    var setup =
        new FlatpakRemoteSourceSetup(
            new ModuleName("flatpak"),
            "flathub",
            URI.create("https://flathub.example/flathub.flatpakrepo"),
            false,
            Optional.of(new Sha256Digest("a".repeat(64))));
    ModuleItem item = ModuleItem.sourceSetupItem(setup, "flathub", ItemType.FLATPAK_REMOTE);

    assertThat(new FlatpakRemoteProbe(runner).probe(item))
        .isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void flatpakProbe_acceptsExactConfiguredScopeNameAndUrl() {
    ShellRunner runner = org.mockito.Mockito.mock(ShellRunner.class);
    when(runner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0, "flathub https://flathub.example/flathub.flatpakrepo\n", "", Duration.ZERO));
    var setup =
        new FlatpakRemoteSourceSetup(
            new ModuleName("flatpak"),
            "flathub",
            URI.create("https://flathub.example/flathub.flatpakrepo"),
            false,
            Optional.of(new Sha256Digest("a".repeat(64))));

    assertThat(
            new FlatpakRemoteProbe(runner)
                .probe(ModuleItem.sourceSetupItem(setup, "flathub", ItemType.FLATPAK_REMOTE)))
        .isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @FunctionalInterface
  private interface InstalledProbeAdapter {
    InstallationStatus probe(ModuleItem item);
  }

  private static final class ContentShellRunner implements ShellRunner {

    private final Map<Path, String> content = new HashMap<>();
    private final Set<Path> nonEmpty = new HashSet<>();

    void content(Path path, String value) {
      content.put(path, value);
    }

    void nonEmpty(Path path) {
      nonEmpty.add(path);
    }

    void empty(Path path) {
      nonEmpty.remove(path);
    }

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      Path path = Path.of(command.getLast());
      if (command.getFirst().equals("cat") && content.containsKey(path)) {
        return new ProcessResult(0, content.get(path), "", Duration.ZERO);
      }
      if (command.getFirst().equals("test") && nonEmpty.contains(path)) {
        return new ProcessResult(0, "", "", Duration.ZERO);
      }
      return new ProcessResult(1, "", "", Duration.ZERO);
    }
  }
}
