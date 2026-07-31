package dev.sysboot.executor;

import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlatpakRemoteInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final PrivilegedArtifactPublisher publisher;

  public FlatpakRemoteInstaller(ShellRunner shellRunner) {
    this(shellRunner, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  FlatpakRemoteInstaller(ShellRunner shellRunner, PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.publisher = publisher;
  }

  StepResult addTrusted(FlatpakRemoteSourceSetup setup, Path descriptor) {
    ProcessResult result;
    try {
      result =
          publisher.consumeVerified(
              descriptor,
              Path.of("/run/sysboot/source-artifact"),
              "0644",
              setup.artifactSha256().orElseThrow(),
              staged -> shellRunner.run(trustedCommand(setup, staged), Map.of(), INSTALL_TIMEOUT));
    } catch (IOException e) {
      return new StepResult.Failure(
          setup.remote(), "Cannot stage verified Flatpak remote descriptor", 1, Duration.ZERO);
    }
    if (result.isSuccess()) {
      return new StepResult.Success(setup.remote(), result.elapsed());
    }
    return new StepResult.Failure(
        setup.remote(), result.stdout() + result.stderr(), result.exitCode(), result.elapsed());
  }

  private List<String> trustedCommand(FlatpakRemoteSourceSetup setup, Path descriptor) {
    var command = new ArrayList<String>();
    command.add("flatpak");
    if (!setup.system()) {
      command.add("--user");
    }
    command.add("remote-add");
    command.add("--if-not-exists");
    command.add(setup.remote());
    command.add(descriptor.toString());
    return List.copyOf(command);
  }
}
