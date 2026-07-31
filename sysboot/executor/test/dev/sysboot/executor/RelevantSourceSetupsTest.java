package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.SourceSetup;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RelevantSourceSetupsTest {

  @Test
  void select_whenOnlyUnrelatedWorkRemains_omitsSourceMutation() {
    List<SourceSetup> setups =
        List.of(
            new FlatpakRemoteSourceSetup(
                new ModuleName("flathub"),
                "flathub",
                URI.create("https://example.test/flathub.flatpakrepo"),
                true,
                Optional.of(new Sha256Digest("a".repeat(64)))));

    assertThat(RelevantSourceSetups.select(setups, List.of(manualPhase()))).isEmpty();
  }

  @Test
  void select_whenAptPackageRemains_keepsOnlyAptSources() {
    var apt =
        new AptRepositorySourceSetup(
            new ModuleName("vendor"),
            "deb [signed-by=/etc/apt/keyrings/vendor.gpg] https://example.test stable main",
            Path.of("/etc/apt/sources.list.d/vendor.list"),
            Optional.empty(),
            Optional.of(Path.of("/etc/apt/keyrings/vendor.gpg")),
            Optional.empty());
    var flatpak =
        new FlatpakRemoteSourceSetup(
            new ModuleName("flathub"),
            "flathub",
            URI.create("https://example.test/flathub.flatpakrepo"),
            true,
            Optional.of(new Sha256Digest("a".repeat(64))));
    var phase =
        phase(
            new PackageModule(
                new ModuleName("apt-packages"),
                PackageManagerKind.APT,
                List.of(new PackageName("curl")),
                false));

    assertThat(RelevantSourceSetups.select(List.of(apt, flatpak), List.of(phase)))
        .containsExactly(apt);
  }

  private static Phase manualPhase() {
    return phase(new ManualModule(new ModuleName("manual"), "Do it", Optional.empty()));
  }

  private static Phase phase(dev.sysboot.core.BootstrapModule module) {
    return new Phase(
        new PhaseName("selected"),
        "Selected work",
        List.of(module),
        List.of(),
        new RestartPolicy.None());
  }
}
