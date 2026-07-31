package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpgKeyExecutorTest {

  private static final String EXPECTED = "A".repeat(40);
  private static final String WRONG = "B".repeat(40);
  private static final String URL = "https://example.test/repository-key.asc";
  private static final Path KEYRING = Path.of("/etc/apt/keyrings/repository.gpg");

  @TempDir Path tempDirectory;

  @Test
  void execute_existingCorrectKeyring_verifiesWithoutDownloadOrMutation() throws Exception {
    Path keyring = Files.writeString(tempDirectory.resolve("existing.gpg"), "existing");
    var runner = new FakeShellRunner(successfulInspection(EXPECTED));
    var downloader = new FakeDownloadClient();

    StepResult result = executor(runner, downloader, keyring).execute(keyringModule());

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(downloader.urls).isEmpty();
    assertThat(runner.commands)
        .singleElement()
        .satisfies(command -> assertStagedInspection(command, keyring));
    assertThat(Files.readString(keyring)).isEqualTo("existing");
  }

  @Test
  void execute_existingWrongKeyring_failsWithoutReplacingOrDeletingIt() throws Exception {
    Path keyring = Files.writeString(tempDirectory.resolve("existing.gpg"), "existing");
    var runner = new FakeShellRunner(successfulInspection(WRONG));
    var downloader = new FakeDownloadClient();

    StepResult result = executor(runner, downloader, keyring).execute(keyringModule());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("fingerprint mismatch");
    assertThat(Files.readString(keyring)).isEqualTo("existing");
    assertThat(downloader.urls).isEmpty();
    assertThat(runner.commands).noneMatch(this::mutatesTrust);
  }

  @Test
  void execute_newKeyring_verifiesRootStageBeforeDecodedPublication() {
    var runner = new FakeShellRunner(successfulInspection(EXPECTED));
    var publisher = new RecordingArtifactPublisher(tempDirectory);
    var executor =
        new GpgKeyExecutor(
            runner,
            new FakeDownloadClient(),
            tempDirectory,
            GpgKeyExecutor.MAX_KEY_BYTES,
            UnaryOperator.identity(),
            publisher);

    StepResult result = executor.execute(keyringModule());

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).hasSize(1);
    assertThat(runner.commands.getFirst().getFirst())
        .isEqualTo(TrustedSystemExecutable.gpg().toString());
    assertThat(publisher.consumedSources)
        .singleElement()
        .satisfies(path -> assertThat(runner.commands.getFirst()).contains(path.toString()));
    assertThat(publisher.publications)
        .singleElement()
        .satisfies(publication -> assertThat(publication.destination()).isEqualTo(KEYRING));
  }

  @Test
  void execute_rpmKeyWithMatchingFingerprint_verifiesBeforeImportingLocalFile() throws Exception {
    var runner = new FakeShellRunner(successfulInspection(EXPECTED), success());
    var downloader = new FakeDownloadClient();
    var publisher = new RecordingArtifactPublisher(tempDirectory);
    var executor =
        new GpgKeyExecutor(
            runner,
            downloader,
            tempDirectory,
            GpgKeyExecutor.MAX_KEY_BYTES,
            UnaryOperator.identity(),
            publisher);

    StepResult result = executor.execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).hasSize(2);
    assertThat(runner.commands.getFirst().getFirst())
        .isEqualTo(TrustedSystemExecutable.gpg().toString());
    assertThat(runner.commands.get(1)).startsWith("sudo", "rpm", "--import");
    assertThat(runner.commands.get(1).getLast())
        .isEqualTo(publisher.consumedSources.getFirst().toString())
        .doesNotContain("https://");
    assertThat(publisher.consumedSources.getFirst()).doesNotExist();
    assertTempFilesCleaned();
  }

  @Test
  void execute_rpmKeyWithWrongFingerprint_failsBeforeTrustMutation() throws Exception {
    var runner = new FakeShellRunner(successfulInspection(WRONG));

    StepResult result = executor(runner, new FakeDownloadClient()).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("fingerprint mismatch");
    assertThat(runner.commands).hasSize(1).noneMatch(this::mutatesTrust);
    assertTempFilesCleaned();
  }

  @Test
  void execute_keyBundleContainingExpectedAndUnexpectedPrimaryKey_failsClosed() {
    var runner = new FakeShellRunner(result(0, inspection(EXPECTED) + inspection(WRONG), ""));

    StepResult result = executor(runner, new FakeDownloadClient()).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).noneMatch(this::mutatesTrust);
  }

  @Test
  void execute_keyBundleContainingPrimaryWithoutFingerprint_failsClosed() {
    String malformedPrimary = "pub:-:4096:1:KEYID:0:0::::::\n";
    var runner = new FakeShellRunner(result(0, malformedPrimary + inspection(EXPECTED), ""));

    StepResult result = executor(runner, new FakeDownloadClient()).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).noneMatch(this::mutatesTrust);
  }

  @Test
  void execute_fingerprintInspectionCommandFails_doesNotMutateTrust() {
    var runner = new FakeShellRunner(result(2, "", "cannot parse key"));

    StepResult result = executor(runner, new FakeDownloadClient()).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("cannot parse key");
    assertThat(runner.commands).hasSize(1).noneMatch(this::mutatesTrust);
  }

  @Test
  void execute_existingKeyringInspectionThrows_failsWithoutMutation() throws Exception {
    Path keyring = Files.writeString(tempDirectory.resolve("existing.gpg"), "existing");
    var runner = new FakeShellRunner();
    runner.failure = new IllegalStateException("gpg unavailable");
    var downloader = new FakeDownloadClient();

    StepResult result = executor(runner, downloader, keyring).execute(keyringModule());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("gpg unavailable");
    assertThat(downloader.urls).isEmpty();
    assertThat(runner.commands)
        .singleElement()
        .satisfies(command -> assertStagedInspection(command, keyring));
  }

  @Test
  void execute_rpmImportCommandFails_reportsFailureAfterVerification() {
    var runner =
        new FakeShellRunner(
            successfulInspection(EXPECTED), result(1, "", "rpm database unavailable"));

    StepResult result = executor(runner, new FakeDownloadClient()).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("rpm database unavailable");
    assertThat(runner.commands).hasSize(2);
    assertThat(runner.commands.getFirst().getFirst())
        .isEqualTo(TrustedSystemExecutable.gpg().toString());
    assertThat(runner.commands.get(1)).startsWith("sudo", "rpm", "--import");
  }

  @Test
  void execute_networkFails_doesNotInvokeGpgOrMutateTrust() {
    var downloader = new FakeDownloadClient();
    downloader.failure = new IOException("network unavailable");
    var runner = new FakeShellRunner();

    StepResult result = executor(runner, downloader).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("network unavailable");
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_continueOnError_attemptsRemainingKeysButStillReportsTrustFailure() {
    var first =
        new GpgKeyModule.GpgKey("https://example.test/first.asc", Optional.empty(), EXPECTED);
    var second =
        new GpgKeyModule.GpgKey("https://example.test/second.asc", Optional.empty(), WRONG);
    var module = new GpgKeyModule(new ModuleName("repository-keys"), List.of(first, second), true);
    var runner =
        new FakeShellRunner(successfulInspection(WRONG), successfulInspection(WRONG), success());

    StepResult result = executor(runner, new FakeDownloadClient()).execute(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).hasSize(3);
    assertThat(runner.commands.getLast()).startsWith("sudo", "rpm", "--import");
  }

  @Test
  void execute_primaryWithoutFingerprintFollowedBySubkeyFingerprint_failsClosed() {
    String output =
        "pub:-:4096:1:PRIMARY:0:0::::::\n"
            + "sub:-:4096:1:SUBKEY:0:0::::::\n"
            + "fpr:::::::::"
            + EXPECTED
            + ":\n";
    var runner = new FakeShellRunner(result(0, output, ""));

    StepResult result = executor(runner, new FakeDownloadClient()).execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).noneMatch(this::mutatesTrust);
  }

  @Test
  void execute_signedUrlUsesFullRequestButNeverReportsQueryOrFragment() {
    String signedUrl = URL + "?token=not-for-output#request-fragment";
    var downloader = new FakeDownloadClient();
    downloader.failure = new IOException("request failed for " + signedUrl);
    var executor = executor(new FakeShellRunner(), downloader);

    StepResult result = executor.execute(rpmModule(signedUrl, EXPECTED));

    assertThat(downloader.urls).containsExactly(URI.create(signedUrl));
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains(URL)
        .doesNotContain("not-for-output", "request-fragment", "?token=");
    assertThat(executor.commandPreview(rpmModule(signedUrl, EXPECTED)))
        .allMatch(line -> !line.contains("not-for-output") && !line.contains("request-fragment"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void execute_oversizedStreamingResponse_failsAtTransferLimitAndCleansPartialFile()
      throws Exception {
    HttpResponse<InputStream> response = org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.uri()).thenReturn(URI.create(URL));
    when(response.body()).thenReturn(new ByteArrayInputStream("12345".getBytes()));
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    var runner = new FakeShellRunner();
    var executor =
        new GpgKeyExecutor(
            runner, new HttpBinaryDownloadClient(httpClient, 4, 4), tempDirectory, 4);

    StepResult result = executor.execute(rpmModule(EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("maximum size of 4 bytes");
    assertThat(runner.commands).isEmpty();
    assertTempFilesCleaned();
  }

  @Test
  void execute_oversizedLocalFile_failsBeforeReadingOrInvokingGpg() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("oversized.asc"), "12345");
    var runner = new FakeShellRunner();
    var executor = new GpgKeyExecutor(runner, new FakeDownloadClient(), tempDirectory, 4);

    StepResult result = executor.execute(rpmModule(source.toUri().toString(), EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("exceeds 4 bytes");
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_localDirectory_failsWithoutInvokingGpg() {
    Path source = tempDirectory.resolve("key-directory");
    assertThat(source.toFile().mkdir()).isTrue();
    var runner = new FakeShellRunner();

    StepResult result =
        executor(runner, new FakeDownloadClient())
            .execute(rpmModule(source.toUri().toString(), EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("regular non-symlink file");
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_localSymlink_failsWithoutFollowingTarget() throws Exception {
    Path target = Files.writeString(tempDirectory.resolve("source-target.asc"), "target");
    Path source = Files.createSymbolicLink(tempDirectory.resolve("source.asc"), target);
    var runner = new FakeShellRunner();

    StepResult result =
        executor(runner, new FakeDownloadClient())
            .execute(rpmModule(source.toUri().toString(), EXPECTED));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("regular non-symlink file");
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_existingSymlinkKeyring_failsWithoutFollowingOrReplacingTarget() throws Exception {
    Path target = Files.writeString(tempDirectory.resolve("target.gpg"), "target");
    Path keyring = Files.createSymbolicLink(tempDirectory.resolve("existing.gpg"), target);
    var runner = new FakeShellRunner();
    var downloader = new FakeDownloadClient();

    StepResult result = executor(runner, downloader, keyring).execute(keyringModule());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("regular non-symlink file");
    assertThat(Files.readString(target)).isEqualTo("target");
    assertThat(Files.isSymbolicLink(keyring)).isTrue();
    assertThat(downloader.urls).isEmpty();
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void key_withoutFingerprint_isRejectedAtDomainBoundary() {
    assertThatThrownBy(() -> new GpgKeyModule.GpgKey(URL, Optional.empty(), null))
        .isInstanceOf(NullPointerException.class);
  }

  private GpgKeyExecutor executor(FakeShellRunner runner, FakeDownloadClient downloader) {
    return new GpgKeyExecutor(
        runner,
        downloader,
        tempDirectory,
        GpgKeyExecutor.MAX_KEY_BYTES,
        UnaryOperator.identity(),
        new RecordingArtifactPublisher(tempDirectory));
  }

  private GpgKeyExecutor executor(
      FakeShellRunner runner, FakeDownloadClient downloader, Path readableKeyring) {
    return new GpgKeyExecutor(
        runner,
        downloader,
        tempDirectory,
        GpgKeyExecutor.MAX_KEY_BYTES,
        ignored -> readableKeyring,
        new RecordingArtifactPublisher(tempDirectory));
  }

  private GpgKeyModule keyringModule() {
    return new GpgKeyModule(
        new ModuleName("repository-key"),
        List.of(new GpgKeyModule.GpgKey(URL, Optional.of(KEYRING), EXPECTED)),
        false);
  }

  private GpgKeyModule rpmModule(String fingerprint) {
    return rpmModule(URL, fingerprint);
  }

  private GpgKeyModule rpmModule(String url, String fingerprint) {
    return new GpgKeyModule(
        new ModuleName("repository-key"),
        List.of(new GpgKeyModule.GpgKey(url, Optional.empty(), fingerprint)),
        false);
  }

  private List<String> inspectCommand(Path path) {
    return List.of(
        TrustedSystemExecutable.gpg().toString(),
        "--batch",
        "--no-options",
        "--show-keys",
        "--with-colons",
        path.toString());
  }

  private void assertStagedInspection(List<String> command, Path keyring) {
    assertThat(command)
        .startsWith(
            TrustedSystemExecutable.gpg().toString(),
            "--batch",
            "--no-options",
            "--show-keys",
            "--with-colons");
    assertThat(command.getLast()).isNotEqualTo(keyring.toString());
    assertThat(Path.of(command.getLast())).doesNotExist();
  }

  private boolean mutatesTrust(List<String> command) {
    return !command.isEmpty() && command.getFirst().equals("sudo");
  }

  private ProcessResult successfulInspection(String fingerprint) {
    return result(0, inspection(fingerprint), "");
  }

  private String inspection(String fingerprint) {
    return "pub:-:4096:1:KEYID:0:0::::::\nfpr:::::::::" + fingerprint + ":\n";
  }

  private ProcessResult success() {
    return result(0, "", "");
  }

  private ProcessResult result(int exitCode, String stdout, String stderr) {
    return new ProcessResult(exitCode, stdout, stderr, Duration.ZERO);
  }

  private void assertTempFilesCleaned() throws IOException {
    try (var files = Files.list(tempDirectory)) {
      assertThat(files).isEmpty();
    }
  }

  private static final class FakeShellRunner implements ShellRunner {

    private final ArrayDeque<ProcessResult> results;
    private final List<List<String>> commands = new ArrayList<>();
    private RuntimeException failure;

    private FakeShellRunner(ProcessResult... results) {
      this.results = new ArrayDeque<>(List.of(results));
    }

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      commands.add(List.copyOf(command));
      if (failure != null) {
        throw failure;
      }
      return results.isEmpty()
          ? new ProcessResult(0, "", "", Duration.ZERO)
          : results.removeFirst();
    }
  }

  private static final class FakeDownloadClient implements BinaryDownloadClient {

    private final List<URI> urls = new ArrayList<>();
    private IOException failure;

    @Override
    public void downloadToFile(URI url, Path destination) throws IOException {
      urls.add(url);
      if (failure != null) {
        throw failure;
      }
      Files.writeString(destination, "downloaded key");
    }

    @Override
    public String downloadText(URI url) {
      throw new UnsupportedOperationException();
    }
  }
}
