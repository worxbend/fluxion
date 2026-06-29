package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.HostFacts;
import dev.sysboot.core.HostFactsProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxHostFactsProviderTest {

  @TempDir Path tempDir;

  @Test
  void facts_whenOsReleasePresent_readsLinuxDistributionFields() throws IOException {
    Path osRelease = tempDir.resolve("os-release");
    Files.writeString(
        osRelease,
        """
        NAME="Ubuntu"
        ID=Ubuntu
        VERSION_ID="24.04"
        VERSION_CODENAME=noble
        """);

    var provider = new LinuxHostFactsProvider(osRelease, Map.of(), Map.of("os.arch", "x86_64"));

    HostFacts facts = provider.facts();
    assertThat(facts.osFamily()).isEqualTo("linux");
    assertThat(facts.distribution()).contains("ubuntu");
    assertThat(facts.version()).contains("24.04");
    assertThat(facts.codename()).contains("noble");
    assertThat(facts.architecture()).isEqualTo("amd64");
  }

  @Test
  void facts_whenUbuntuCodenamePresent_usesUbuntuCodenameFallback() throws IOException {
    Path osRelease = tempDir.resolve("os-release");
    Files.writeString(osRelease, "ID=ubuntu\nVERSION_ID=22.04\nUBUNTU_CODENAME=jammy\n");

    var provider = new LinuxHostFactsProvider(osRelease, Map.of(), Map.of("os.arch", "aarch64"));

    assertThat(provider.facts().codename()).contains("jammy");
    assertThat(provider.facts().architecture()).isEqualTo("arm64");
  }

  @Test
  void facts_whenOsReleaseMissing_returnsLinuxFactsWithEmptyDistribution() {
    var provider =
        new LinuxHostFactsProvider(
            tempDir.resolve("missing-os-release"), Map.of(), Map.of("os.arch", "amd64"));

    HostFacts facts = provider.facts();
    assertThat(facts.osFamily()).isEqualTo("linux");
    assertThat(facts.distribution()).isEmpty();
    assertThat(facts.version()).isEmpty();
    assertThat(facts.codename()).isEmpty();
    assertThat(facts.architecture()).isEqualTo("amd64");
  }

  @Test
  void commandExists_whenExecutableIsOnPath_returnsTrue() throws IOException {
    Path bin = tempDir.resolve("bin");
    Files.createDirectory(bin);
    Path command = bin.resolve("fluxion-test-tool");
    Files.writeString(command, "#!/bin/sh\n");
    assertThat(command.toFile().setExecutable(true, true)).isTrue();

    var provider =
        new LinuxHostFactsProvider(
            tempDir.resolve("os-release"), Map.of("PATH", bin.toString()), Map.of("os.arch", "x86_64"));

    assertThat(provider.commandExists("fluxion-test-tool")).isTrue();
    assertThat(provider.commandExists("missing-tool")).isFalse();
  }

  @Test
  void commandExists_whenCommandContainsPathSeparator_returnsFalse() {
    var provider =
        new LinuxHostFactsProvider(
            tempDir.resolve("os-release"), Map.of("PATH", tempDir.toString()), Map.of("os.arch", "x86_64"));

    assertThat(provider.commandExists("./tool")).isFalse();
    assertThat(provider.commandExists("")).isFalse();
  }

  @Test
  void hostFactsProvider_canBeFakedInTests() {
    HostFactsProvider provider =
        new HostFactsProvider() {
          @Override
          public HostFacts facts() {
            return new HostFacts(
                "linux",
                Optional.of("debian"),
                Optional.of("12"),
                Optional.of("bookworm"),
                "amd64");
          }

          @Override
          public boolean commandExists(String command) {
            return "apt".equals(command);
          }
        };

    assertThat(provider.facts().distribution()).contains("debian");
    assertThat(provider.commandExists("apt")).isTrue();
  }
}
