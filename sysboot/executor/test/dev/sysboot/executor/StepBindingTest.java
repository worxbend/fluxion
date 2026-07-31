package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitConfigScope;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.ToolchainKind;
import dev.sysboot.core.ToolchainModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The binding table replaced twenty-eight hand-written switch arms. Its value depends on staying
 * complete and on not claiming kinds that need bespoke handling, so both are asserted.
 */
class StepBindingTest {

  @Test
  void aKindWithItsOwnItemKeyUsesIt() {
    var module =
        new SystemUpdateModule(
            new ModuleName("update"),
            PackageManagerKind.ZYPPER,
            true,
            false,
            Optional.empty(),
            false);

    assertThat(StepBinding.find(module).orElseThrow().itemKey(module))
        .isEqualTo("zypper-system-update");
  }

  @Test
  void kindsThatNeedBespokeHandlingAreDeliberatelyNotBound() {
    // Packages loop per item through their own executor registry. Binding them here would collapse
    // sixty packages into one item and lose per-package failure isolation.
    BootstrapModule packages =
        new PackageModule(
            new ModuleName("core"),
            PackageManagerKind.DNF,
            List.of(new dev.sysboot.core.PackageName("git")),
            false);

    assertThat(StepBinding.find(packages)).isEmpty();
    assertThat(
            StepBinding.find(
                new GitConfigModule(
                    new ModuleName("git"), GitConfigScope.GLOBAL, Map.of("user.name", "x"), false)))
        .isEmpty();
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("canonicalSingleItemKeys")
  void singleItemBindingUsesCanonicalExecutionPlanAndProbeKey(
      BootstrapModule module, String expectedKey) {
    var binding = StepBinding.find(module).orElseThrow();

    assertThat(binding.item(module).key()).isEqualTo(expectedKey);
    assertThat(ModuleItemCatalog.items(module)).containsExactly(binding.item(module));
  }

  static Stream<Arguments> canonicalSingleItemKeys() {
    Sha256Digest digest = new Sha256Digest("a".repeat(64));
    return Stream.of(
        Arguments.of(
            new DotbotModule(
                new ModuleName("dotfiles"),
                Path.of("/home/test/dotfiles.yaml"),
                "v1.0.0",
                "dotbot",
                Optional.empty()),
            "/home/test/dotfiles.yaml"),
        Arguments.of(
            new DefaultShellModule(new ModuleName("shell"), Path.of("/bin/zsh"), Optional.empty()),
            "/bin/zsh"),
        Arguments.of(
            new OhMyZshModule(
                new ModuleName("omz"),
                Path.of("/home/test/.oh-my-zsh"),
                "a".repeat(40),
                digest,
                Optional.empty()),
            "/home/test/.oh-my-zsh"),
        Arguments.of(
            new ToolchainModule(
                new ModuleName("rust"),
                ToolchainKind.RUSTUP,
                "https://example.test/rustup.sh",
                digest,
                List.of("-y"),
                Optional.empty(),
                Optional.empty(),
                false),
            "rustup"),
        Arguments.of(
            new NerdFontModule(
                new ModuleName("fonts"),
                "v1.0.0",
                "nerdfont-install",
                new NerdFontConfig(
                    "v3.0.0",
                    Path.of("/home/test/.local/share/fonts"),
                    true,
                    List.of("JetBrainsMono")),
                Optional.empty()),
            "JetBrainsMono"),
        Arguments.of(new ShellReloadModule(new ModuleName("reload"), ShellKind.ZSH), "zsh"));
  }
}
