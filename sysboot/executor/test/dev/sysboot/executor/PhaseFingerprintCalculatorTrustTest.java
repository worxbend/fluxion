package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GitRepoUpdate;
import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellEnvironmentVariable;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.ToolchainKind;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperRepositoryModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhaseFingerprintCalculatorTrustTest {

  private final PhaseFingerprintCalculator calculator = new PhaseFingerprintCalculator();

  @TempDir Path tempDir;

  @Test
  void fingerprint_whenRemoteScriptSha256Changes_changesFingerprint() {
    String first = fingerprint(shellScript("a".repeat(64)));
    String second = fingerprint(shellScript("b".repeat(64)));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void fingerprints_whenShellScriptEnvironmentChanges_change() {
    var baseline = shellScript(List.of(environment("SETTING", "value-one", false)));

    assertFingerprintAndManifestChange(
        baseline, shellScript(List.of(environment("RENAMED", "value-one", false))));
    assertFingerprintAndManifestChange(
        baseline, shellScript(List.of(environment("SETTING", "value-two", false))));
    assertFingerprintAndManifestChange(
        baseline, shellScript(List.of(environment("SETTING", "value-one", true))));
  }

  @Test
  void fingerprints_whenShellScriptEnvironmentOrderChanges_remainStable() {
    var first =
        shellScript(
            List.of(
                environment("FIRST_SETTING", "value-one", false),
                environment("SECOND_SETTING", "value-two", true)));
    var reordered =
        shellScript(
            List.of(
                environment("SECOND_SETTING", "value-two", true),
                environment("FIRST_SETTING", "value-one", false)));

    assertThat(fingerprint(first)).isEqualTo(fingerprint(reordered));
    assertThat(manifestFingerprint(first)).isEqualTo(manifestFingerprint(reordered));
  }

  @Test
  void fingerprints_whenSensitiveEnvironmentValueChanges_changeWithoutPersistingPlaintext() {
    var first = shellScript(List.of(environment("API_TOKEN", "short-one", true)));
    var second = shellScript(List.of(environment("API_TOKEN", "short-two", true)));

    assertFingerprintAndManifestChange(first, second);
  }

  @Test
  void fingerprints_whenLocalScriptBytesChange_change() throws Exception {
    Path script = tempDir.resolve("setup.sh");
    Files.writeString(script, "printf first");
    ShellScriptModule module = localScript(script);
    String first = fingerprint(module);
    String firstManifest = manifestFingerprint(module);

    Files.writeString(script, "printf second");

    assertThat(fingerprint(module)).isNotEqualTo(first);
    assertThat(manifestFingerprint(module)).isNotEqualTo(firstManifest);
  }

  @Test
  void fingerprints_whenFileSourceBytesChange_change() throws Exception {
    Path source = tempDir.resolve("managed.conf");
    Files.writeString(source, "first=true");
    FileWriteModule module = fileWrite(source);
    String first = fingerprint(module);
    String firstManifest = manifestFingerprint(module);

    Files.writeString(source, "first=false");

    assertThat(fingerprint(module)).isNotEqualTo(first);
    assertThat(manifestFingerprint(module)).isNotEqualTo(firstManifest);
  }

  @Test
  void fingerprints_whenOnlyRequestUrlCredentialsChange_remainStable() {
    CompiledBinaryModule first = binaryWithUrl("https://example.test/tool?token=first#private");
    CompiledBinaryModule second = binaryWithUrl("https://example.test/tool?token=second#other");

    assertThat(fingerprint(first)).isEqualTo(fingerprint(second));
    assertThat(manifestFingerprint(first)).isEqualTo(manifestFingerprint(second));
  }

  @Test
  void manifestFingerprint_whenSourceRequestCredentialsChange_remainsStable() {
    assertThat(credentialedSourceManifestFingerprint("first"))
        .isEqualTo(credentialedSourceManifestFingerprint("second"));
  }

  @Test
  void fingerprint_whenToolchainSha256Changes_changesFingerprint() {
    String first = fingerprint(toolchain("a".repeat(64)));
    String second = fingerprint(toolchain("b".repeat(64)));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void fingerprint_whenOhMyZshTrustInputsChange_changesFingerprint() {
    String baseline = fingerprint(ohMyZsh("a".repeat(40), "a".repeat(64)));

    assertThat(fingerprint(ohMyZsh("b".repeat(40), "a".repeat(64)))).isNotEqualTo(baseline);
    assertThat(fingerprint(ohMyZsh("a".repeat(40), "b".repeat(64)))).isNotEqualTo(baseline);
  }

  @Test
  void fingerprint_whenCompiledBinaryAllowedSignerChanges_changesFingerprint() {
    String first = fingerprint(signedBinary("a".repeat(40)));
    String second = fingerprint(signedBinary("b".repeat(40)));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void fingerprint_whenGpgKeyFingerprintChanges_changesFingerprint() {
    String first = fingerprint(gpgKey("a".repeat(40)));
    String second = fingerprint(gpgKey("b".repeat(40)));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void manifestFingerprint_whenSourceArtifactChecksumChanges_changesFingerprint() {
    String first = sourceManifestFingerprint("a".repeat(64));
    String second = sourceManifestFingerprint("b".repeat(64));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void fingerprint_whenDirectRepositoryChecksumChanges_changesForEveryArtifactKind() {
    List.of(
            new ModulePair(aptRepository("a"), aptRepository("b")),
            new ModulePair(rpmRepository("a"), rpmRepository("b")),
            new ModulePair(zypperRepository("a"), zypperRepository("b")),
            new ModulePair(flatpakRemote("a"), flatpakRemote("b")))
        .forEach(
            pair -> assertThat(fingerprint(pair.first())).isNotEqualTo(fingerprint(pair.second())));
  }

  @Test
  void fingerprint_whenGitExecutionInputsChange_changesFingerprint() {
    var baseline = gitRepo(Optional.of(1), false, false);

    assertFingerprintAndManifestChange(baseline, gitRepo(Optional.of(2), false, false));
    assertFingerprintAndManifestChange(baseline, gitRepo(Optional.of(1), true, false));
    assertFingerprintAndManifestChange(baseline, gitRepo(Optional.of(1), false, true));
  }

  @Test
  void fingerprint_whenShellCommandEnvironmentChanges_changesFingerprint() {
    var baseline = shellCommand(environment("SETTING", "one", false));

    assertFingerprintAndManifestChange(
        baseline, shellCommand(environment("SETTING", "two", false)));
    assertFingerprintAndManifestChange(baseline, shellCommand(environment("SETTING", "one", true)));
  }

  @Test
  void fingerprint_whenDelegatedToolOrFontInputsChange_changesFingerprint() {
    assertFingerprintAndManifestChange(
        binstaller("binstaller", Optional.empty()),
        binstaller("custom-binstaller", Optional.empty()));
    assertFingerprintAndManifestChange(
        binstaller("binstaller", Optional.empty()),
        binstaller("binstaller", Optional.of("test -x binstaller")));
    assertFingerprintAndManifestChange(
        nerdFont(Path.of("/tmp/first.yaml")), nerdFont(Path.of("/tmp/second.yaml")));
  }

  @Test
  void fingerprint_whenExternalToolConfigBytesChange_changesFingerprint(@TempDir Path directory)
      throws Exception {
    Path dotbotConfig = Files.writeString(directory.resolve("dotbot.yaml"), "one");
    var dotbot =
        new DotbotModule(
            new ModuleName("dotbot"), dotbotConfig, "v0.4.2", "dotbot", Optional.empty());
    String dotbotBefore = fingerprint(dotbot);
    Files.writeString(dotbotConfig, "two");
    assertThat(fingerprint(dotbot)).isNotEqualTo(dotbotBefore);

    Path toolsConfig = Files.writeString(directory.resolve("tools.yaml"), "one");
    Path toolsLock = Files.writeString(directory.resolve("tools.lock"), "one");
    var tools =
        new BinstallerModule(
            new ModuleName("binstaller"),
            toolsConfig,
            List.of(),
            List.of(),
            true,
            Optional.of(toolsLock),
            "v1.0.7",
            "binstaller",
            Optional.empty(),
            false);
    String toolsBefore = fingerprint(tools);
    Files.writeString(toolsLock, "two");
    assertThat(fingerprint(tools)).isNotEqualTo(toolsBefore);

    Path fontConfig = Files.writeString(directory.resolve("fonts.yaml"), "one");
    NerdFontModule fonts = nerdFont(fontConfig);
    String fontsBefore = fingerprint(fonts);
    Files.writeString(fontConfig, "two");
    assertThat(fingerprint(fonts)).isNotEqualTo(fontsBefore);
  }

  @Test
  void fingerprint_whenContinuationAndTimeoutInputsChange_changesFingerprint() {
    assertFingerprintAndManifestChange(
        new FlatpakModule(new ModuleName("apps"), "flathub", List.of("org.example.App"), false),
        new FlatpakModule(new ModuleName("apps"), "flathub", List.of("org.example.App"), true));
    assertFingerprintAndManifestChange(
        systemUpdate(Duration.ofMinutes(10), false), systemUpdate(Duration.ofMinutes(20), false));
    assertFingerprintAndManifestChange(
        systemUpdate(Duration.ofMinutes(10), false), systemUpdate(Duration.ofMinutes(10), true));
  }

  private String fingerprint(BootstrapModule module) {
    return calculator.fingerprint(phase(module));
  }

  private String credentialedSourceManifestFingerprint(String token) {
    BootstrapConfig config =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("44"))
            .sourceSetups(
                List.of(
                    new FlatpakRemoteSourceSetup(
                        new ModuleName("flathub"),
                        "flathub",
                        URI.create(
                            "https://example.test/flathub.flatpakrepo?token=" + token + "#private"),
                        true,
                        Optional.of(new Sha256Digest("a".repeat(64))))))
            .addPhase(phase(shellScript("c".repeat(64))))
            .build();
    return calculator.manifestFingerprint(config);
  }

  private CompiledBinaryModule binaryWithUrl(String url) {
    return new CompiledBinaryModule(
        new ModuleName("tool"),
        "tool",
        new BinaryUrl(URI.create(url)),
        Optional.of(new dev.sysboot.core.Checksum("sha256", "a".repeat(64))),
        Path.of("/usr/local/bin/tool"),
        false);
  }

  private String manifestFingerprint(BootstrapModule module) {
    var config =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("44"))
            .addPhase(phase(module))
            .build();
    return calculator.manifestFingerprint(config);
  }

  private String sourceManifestFingerprint(String sha256) {
    var source =
        new FlatpakRemoteSourceSetup(
            new ModuleName("flathub"),
            "flathub",
            URI.create("https://example.test/flathub.flatpakrepo"),
            false,
            Optional.of(new Sha256Digest(sha256)));
    var config =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("44"))
            .sourceSetups(List.of(source))
            .addPhase(phase(shellScript("c".repeat(64))))
            .build();
    return calculator.manifestFingerprint(config);
  }

  private AptRepositoryModule aptRepository(String digest) {
    return new AptRepositoryModule(
        new ModuleName("apt"),
        "deb [signed-by=/etc/apt/keyrings/example.gpg]"
            + " https://example.test/debian stable main",
        Path.of("/etc/apt/sources.list.d/example.list"),
        Optional.of(URI.create("https://example.test/apt.key")),
        Optional.of(Path.of("/etc/apt/keyrings/example.gpg")),
        Optional.of(sha256(digest)));
  }

  private RpmRepositoryModule rpmRepository(String digest) {
    return new RpmRepositoryModule(
        new ModuleName("rpm"),
        "rpm",
        URI.create("https://example.test/rpm"),
        Path.of("/etc/yum.repos.d/example.repo"),
        Optional.of(URI.create("https://example.test/rpm.key")),
        true,
        true,
        Optional.of(sha256(digest)));
  }

  private ZypperRepositoryModule zypperRepository(String digest) {
    return new ZypperRepositoryModule(
        new ModuleName("zypper"),
        "zypper",
        URI.create("https://example.test/zypper"),
        Path.of("/etc/zypp/repos.d/example.repo"),
        Optional.of(URI.create("https://example.test/zypper.key")),
        true,
        true,
        true,
        Optional.of(sha256(digest)));
  }

  private FlatpakRemoteModule flatpakRemote(String digest) {
    return new FlatpakRemoteModule(
        new ModuleName("flathub"),
        "flathub",
        URI.create("https://example.test/flathub.flatpakrepo"),
        true,
        Optional.of(sha256(digest)));
  }

  private Sha256Digest sha256(String value) {
    return new Sha256Digest(value.repeat(64));
  }

  private Phase phase(BootstrapModule module) {
    return new Phase(
        new PhaseName("trust"),
        "Trust policy",
        List.of(module),
        List.of(),
        new RestartPolicy.None(),
        false);
  }

  private void assertFingerprintAndManifestChange(
      BootstrapModule baseline, BootstrapModule changed) {
    assertThat(fingerprint(changed)).isNotEqualTo(fingerprint(baseline));
    assertThat(manifestFingerprint(changed)).isNotEqualTo(manifestFingerprint(baseline));
  }

  private ShellScriptModule shellScript(String sha256) {
    return shellScript(sha256, List.of());
  }

  private ShellScriptModule shellScript(List<ShellEnvironmentVariable> environment) {
    return shellScript("a".repeat(64), environment);
  }

  private ShellScriptModule shellScript(String sha256, List<ShellEnvironmentVariable> environment) {
    var item =
        new ShellScriptItem(
            "remote",
            Optional.empty(),
            Optional.of(URI.create("https://example.test/install.sh")),
            List.of(),
            Optional.empty(),
            environment,
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofMinutes(1),
            Optional.of(new Sha256Digest(sha256)));
    return new ShellScriptModule(
        new ModuleName("scripts"), List.of(item), Optional.empty(), false, Optional.empty());
  }

  private ShellScriptModule localScript(Path script) {
    return new ShellScriptModule(
        new ModuleName("local-script"),
        List.of(
            ShellScriptItem.local(
                new dev.sysboot.core.ScriptPath(script), List.of(), Optional.empty())),
        Optional.empty(),
        false,
        Optional.empty());
  }

  private FileWriteModule fileWrite(Path source) {
    var item =
        new FileWriteItem(
            "managed",
            Path.of("/tmp/managed.conf"),
            Optional.empty(),
            Optional.of(source),
            Optional.empty(),
            Optional.empty(),
            Optional.of("0600"),
            false);
    return new FileWriteModule(new ModuleName("file-write"), List.of(item), false);
  }

  private ShellEnvironmentVariable environment(String name, String value, boolean sensitive) {
    return new ShellEnvironmentVariable(name, value, sensitive);
  }

  private ToolchainModule toolchain(String sha256) {
    return new ToolchainModule(
        new ModuleName("rustup"),
        ToolchainKind.RUSTUP,
        "https://example.test/install.sh",
        new Sha256Digest(sha256),
        List.of(),
        Optional.empty(),
        Optional.empty(),
        false);
  }

  private OhMyZshModule ohMyZsh(String revision, String sha256) {
    return new OhMyZshModule(
        new ModuleName("oh-my-zsh"),
        Path.of(".oh-my-zsh"),
        revision,
        new Sha256Digest(sha256),
        Optional.empty());
  }

  private record ModulePair(BootstrapModule first, BootstrapModule second) {}

  private CompiledBinaryModule signedBinary(String signerFingerprint) {
    return new CompiledBinaryModule(
        new ModuleName("binary"),
        "binary",
        new BinaryUrl(URI.create("https://example.test/binary")),
        Optional.empty(),
        Optional.empty(),
        Optional.of(new BinaryUrl(URI.create("https://example.test/binary.asc"))),
        Path.of("/usr/local/bin/binary"),
        Optional.empty(),
        0,
        Optional.of("0755"),
        Optional.empty(),
        false,
        Optional.empty(),
        Optional.empty(),
        Optional.of(signerFingerprint));
  }

  private GpgKeyModule gpgKey(String signerFingerprint) {
    var key =
        new GpgKeyModule.GpgKey(
            "https://example.test/repository-key.asc", Optional.empty(), signerFingerprint);
    return new GpgKeyModule(new ModuleName("repository-key"), List.of(key), false);
  }

  private GitRepoModule gitRepo(
      Optional<Integer> depth, boolean submodules, boolean continueOnError) {
    var repo =
        new GitRepoModule.GitRepo(
            "https://example.test/repository.git",
            "/tmp/repository",
            Optional.of("0123456789abcdef0123456789abcdef01234567"),
            depth,
            submodules,
            GitRepoUpdate.NONE);
    return new GitRepoModule(new ModuleName("repositories"), List.of(repo), continueOnError);
  }

  private ShellCommandModule shellCommand(ShellEnvironmentVariable environment) {
    var item =
        new ShellCommandItem(
            "command",
            Optional.of("printf done"),
            Optional.empty(),
            "/bin/sh",
            Optional.empty(),
            List.of(environment),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofMinutes(1));
    return new ShellCommandModule(
        new ModuleName("commands"),
        List.of(item),
        "/bin/sh",
        Optional.empty(),
        false,
        Optional.empty());
  }

  private BinstallerModule binstaller(String binary, Optional<String> probe) {
    return new BinstallerModule(
        new ModuleName("binstaller"),
        Path.of("/tmp/tools.yaml"),
        List.of(),
        List.of(),
        true,
        Optional.of(Path.of("/tmp/tools.lock")),
        "v1.0.7",
        binary,
        probe,
        false);
  }

  private NerdFontModule nerdFont(Path configPath) {
    return new NerdFontModule(
        new ModuleName("fonts"),
        "v1.0.7",
        "nerd-fonts-installer",
        new NerdFontConfig("v3.4.0", Path.of("/tmp/fonts"), true, List.of("FiraCode")),
        Optional.of(configPath),
        Optional.empty());
  }

  private SystemUpdateModule systemUpdate(Duration timeout, boolean continueOnError) {
    return new SystemUpdateModule(
        new ModuleName("update"),
        PackageManagerKind.DNF,
        false,
        false,
        Optional.of(timeout),
        continueOnError);
  }
}
