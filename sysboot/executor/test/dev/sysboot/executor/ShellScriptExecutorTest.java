package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ExecutionApproval;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellEnvironmentVariable;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ShellScriptExecutorTest {

  @Test
  void execute_whenSudoScriptUsesUrl_runsOnlyDigestVerifiedRootStage(@TempDir Path tempDir)
      throws Exception {
    var runner = new FakeShellRunner();
    String content = "#!/bin/bash\necho ok\n";
    var downloader = new FakeDownloadClient(content);
    var publisher = new StagingPublisher(tempDir);
    URI signedUrl =
        URI.create(
            "https://example.test/install.sh?X-Amz-Signature=sensitive-signature"
                + "#sensitive-fragment");
    var item =
        new ShellScriptItem(
            "remote-script",
            Optional.empty(),
            Optional.of(signedUrl),
            List.of("--dry"),
            Optional.empty(),
            List.of(new ShellEnvironmentVariable("API_TOKEN", "secret", true)),
            true,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofSeconds(5),
            Optional.of(ArtifactDigests.sha256(content.getBytes())));

    StepResult result =
        new ShellScriptExecutor(runner, downloader, ExecutionApproval.denyAll(), publisher)
            .execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(downloader.urls).containsExactly(signedUrl);
    assertThat(runner.commands).hasSize(1);
    assertThat(runner.commands.getFirst()).startsWith("sudo", "/bin/bash");
    assertThat(runner.commands.getFirst()).endsWith("--dry");
    assertThat(Path.of(runner.commands.getFirst().get(2)))
        .isNotEqualTo(downloader.downloaded)
        .doesNotExist();
    assertThat(runner.sensitiveEnvironment.getFirst())
        .extracting(ShellEnvironmentVariable::name)
        .containsExactly("API_TOKEN");
    assertThat(Files.exists(Path.of(runner.commands.getFirst().get(2)))).isFalse();
  }

  @Test
  void execute_whenPrivilegedRootStageDigestIsRejected_neverRunsScript() {
    var runner = new FakeShellRunner();
    var downloader = new FakeDownloadClient("#!/bin/bash\necho ok\n");
    var item =
        new ShellScriptItem(
            "remote-script",
            Optional.empty(),
            Optional.of(URI.create("https://example.test/install.sh")),
            List.of(),
            Optional.empty(),
            List.of(),
            true,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofSeconds(5),
            Optional.of(new Sha256Digest("0".repeat(64))));

    StepResult result =
        new ShellScriptExecutor(
                runner, downloader, ExecutionApproval.denyAll(), new RejectingPublisher())
            .execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_whenVerificationFails_doesNotRunScript() {
    var runner = new FakeShellRunner();
    ScriptDownloadClient failing =
        (url, sha256) -> {
          throw new IOException("SHA-256 mismatch");
        };

    StepResult result = new ShellScriptExecutor(runner, failing).execute(module(remoteItem()));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .isEqualTo("Remote script download or SHA-256 verification failed")
        .doesNotContain("SHA-256 mismatch")
        .doesNotContain("example.test");
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_whenVerificationFailsAndContinueOnError_runsFollowingLocalScript() throws Exception {
    var runner = new FakeShellRunner();
    ScriptDownloadClient failing =
        (url, sha256) -> {
          throw new IOException("SHA-256 mismatch");
        };
    Path localScript = Files.createTempFile("following-script-", ".sh");
    Files.writeString(localScript, "#!/bin/sh\n");
    var local =
        ShellScriptItem.local(
            new dev.sysboot.core.ScriptPath(localScript), List.of(), Optional.empty());
    var module =
        new ShellScriptModule(
            new ModuleName("scripts"),
            List.of(remoteItem(), local),
            Optional.empty(),
            true,
            Optional.empty());

    try {
      StepResult result = new ShellScriptExecutor(runner, failing).execute(module);

      assertThat(result).isInstanceOf(StepResult.Failure.class);
      assertThat(runner.commands).hasSize(1);
      assertThat(runner.commands.getFirst()).contains(localScript.toString());
    } finally {
      Files.deleteIfExists(localScript);
    }
  }

  @Test
  void execute_whenUnlessMatches_registersSensitiveEnvironment() {
    var runner = new FakeShellRunner();
    var item = remoteItemWithUnless();

    StepResult result =
        new ShellScriptExecutor(runner, (url, sha256) -> Path.of("unused")).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).containsExactly(List.of("/bin/bash", "-lc", "test -d ~/.sdkman"));
    assertThat(runner.sensitiveEnvironment.getFirst())
        .extracting(ShellEnvironmentVariable::name)
        .containsExactly("API_TOKEN");
  }

  @Test
  void execute_appliesWorkingDirectoryToRelativePaths(@TempDir Path directory) throws Exception {
    Path script = directory.resolve("create-relative.sh");
    Files.writeString(script, "#!/bin/sh\ntouch script-created\n");
    var item =
        ShellScriptItem.local(
            new dev.sysboot.core.ScriptPath(script), List.of(), Optional.of(directory));

    StepResult result = new ShellScriptExecutor(new DefaultShellRunner()).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(directory.resolve("script-created")).exists();
  }

  @Test
  void execute_whenLocalScriptUsesInterpreter_doesNotChmodSource(@TempDir Path directory)
      throws Exception {
    Path script = directory.resolve("read-only-source.sh");
    Files.writeString(script, "#!/bin/sh\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-r-----"));
    var item =
        ShellScriptItem.local(new dev.sysboot.core.ScriptPath(script), List.of(), Optional.empty());

    StepResult result = new ShellScriptExecutor(new FakeShellRunner()).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))
        .isEqualTo("rw-r-----");
  }

  @Test
  void execute_whenLocalScriptIsSymbolicLink_rejectsBeforeRunning(@TempDir Path directory)
      throws Exception {
    Path target = directory.resolve("target.sh");
    Path link = directory.resolve("linked.sh");
    Files.writeString(target, "#!/bin/sh\n");
    Files.createSymbolicLink(link, target);
    var runner = new FakeShellRunner();

    StepResult result = new ShellScriptExecutor(runner).execute(module(localItem(link)));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void execute_whenLocalScriptIsFifo_rejectsBeforeOpening(@TempDir Path directory)
      throws Exception {
    Path fifo = directory.resolve("blocking.fifo");
    assertThat(new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start().waitFor()).isZero();
    var runner = new FakeShellRunner();

    StepResult result = new ShellScriptExecutor(runner).execute(module(localItem(fifo)));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_whenLocalScriptExceedsBound_rejectsBeforeRunning(@TempDir Path directory)
      throws Exception {
    Path script = directory.resolve("oversized.sh");
    Files.writeString(script, "#!/bin/sh\n");
    try (FileChannel channel = FileChannel.open(script, StandardOpenOption.WRITE)) {
      channel.position(64L * 1024 * 1024);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    var runner = new FakeShellRunner();

    StepResult result = new ShellScriptExecutor(runner).execute(module(localItem(script)));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_whenLocalScriptHasLongBody_readsOnlyShebangPrefix(@TempDir Path directory)
      throws Exception {
    Path script = directory.resolve("long.sh");
    Files.writeString(script, "#!/bin/sh\n" + "#".repeat(1_000_000));
    var runner = new FakeShellRunner();

    StepResult result = new ShellScriptExecutor(runner).execute(module(localItem(script)));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands.getFirst()).startsWith("/bin/sh", script.toString());
  }

  @Test
  void commandPreview_whenRemoteUrlIsSigned_omitsQueryAndFragment() {
    URI signedUrl =
        URI.create(
            "https://example.test/install.sh?X-Amz-Signature=sensitive-signature"
                + "#sensitive-fragment");
    ShellScriptItem item = remoteItem(signedUrl, Optional.empty());

    List<String> preview =
        new ShellScriptExecutor(new FakeShellRunner(), (url, sha256) -> Path.of("unused"))
            .commandPreview(item);

    assertThat(preview)
        .anyMatch(value -> value.endsWith("example.test/install.sh"))
        .noneMatch(
            value ->
                value.contains("X-Amz-Signature")
                    || value.contains("sensitive-signature")
                    || value.contains("sensitive-fragment"));
    assertThat(item.url()).contains(signedUrl);
  }

  @Test
  void execute_whenConfirmationIsRequired_deniesByDefaultAndRunsWhenApproved(
      @TempDir Path directory) throws Exception {
    Path marker = directory.resolve("confirmed-script");
    Path script = directory.resolve("guarded.sh");
    Files.writeString(script, "#!/bin/sh\ntouch " + marker + "\n");
    var item =
        new ShellScriptItem(
            "guarded-script",
            Optional.of(new dev.sysboot.core.ScriptPath(script)),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            List.of(),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.of("Run the script?"),
            Duration.ofSeconds(5),
            Optional.empty());

    StepResult denied = new ShellScriptExecutor(new DefaultShellRunner()).execute(module(item));

    assertThat(denied).isInstanceOf(StepResult.Failure.class);
    assertThat(marker).doesNotExist();

    StepResult approved =
        new ShellScriptExecutor(new DefaultShellRunner(), ExecutionApproval.approveAll())
            .execute(module(item));

    assertThat(approved).isInstanceOf(StepResult.Success.class);
    assertThat(marker).exists();
  }

  private ShellScriptModule module(ShellScriptItem item) {
    return new ShellScriptModule(
        new ModuleName("scripts"), List.of(item), Optional.empty(), false, Optional.empty());
  }

  private ShellScriptItem localItem(Path path) {
    return ShellScriptItem.local(
        new dev.sysboot.core.ScriptPath(path), List.of(), Optional.empty());
  }

  private ShellScriptItem remoteItem() {
    return remoteItem(Optional.empty());
  }

  private ShellScriptItem remoteItemWithUnless() {
    return remoteItem(Optional.of("test -d ~/.sdkman"));
  }

  private ShellScriptItem remoteItem(Optional<String> unless) {
    return remoteItem(URI.create("https://example.test/install.sh"), unless);
  }

  private ShellScriptItem remoteItem(URI url, Optional<String> unless) {
    return new ShellScriptItem(
        "remote-script",
        Optional.empty(),
        Optional.of(url),
        List.of(),
        Optional.empty(),
        List.of(new ShellEnvironmentVariable("API_TOKEN", "secret", true)),
        false,
        List.of(0),
        Optional.empty(),
        unless,
        Optional.empty(),
        Duration.ofSeconds(5),
        Optional.of(
            new Sha256Digest("0000000000000000000000000000000000000000000000000000000000000000")));
  }

  private static final class FakeShellRunner implements dev.sysboot.core.ShellRunner {
    private final ArrayList<List<String>> commands = new ArrayList<>();
    private final ArrayList<List<ShellEnvironmentVariable>> sensitiveEnvironment =
        new ArrayList<>();

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(command);
      sensitiveEnvironment.add(ExecutionOutput.sensitiveEnvironment());
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }

  private static final class FakeDownloadClient implements ScriptDownloadClient {
    private final String content;
    private final ArrayList<URI> urls = new ArrayList<>();
    private Path downloaded;

    private FakeDownloadClient(String content) {
      this.content = content;
    }

    @Override
    public Path download(URI url, Sha256Digest sha256) throws IOException {
      urls.add(url);
      Path destination = Files.createTempFile("shell-script-test-", ".sh");
      Files.writeString(destination, content);
      downloaded = destination;
      return destination;
    }
  }

  private static final class StagingPublisher implements PrivilegedArtifactPublisher {
    private final Path tempDirectory;

    private StagingPublisher(Path tempDirectory) {
      this.tempDirectory = tempDirectory;
    }

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      Path staged = Files.copy(source, tempDirectory.resolve("root-stage"));
      try {
        Files.writeString(source, "#!/tmp/attacker\n");
        if (!ArtifactDigests.sha256(staged).equals(expected)) {
          return new ProcessResult(1, "", "digest mismatch", Duration.ZERO);
        }
        return consumer.consume(staged);
      } finally {
        Files.deleteIfExists(staged);
      }
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class RejectingPublisher implements PrivilegedArtifactPublisher {

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        Sha256Digest expected,
        StagedConsumer consumer) {
      return new ProcessResult(1, "", "digest mismatch", Duration.ZERO);
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }
}
