package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell runner that can answer a {@code sudo} password prompt on the child's stdin.
 *
 * <p>The password never becomes a {@code String}: it is encoded straight from the caller's {@code
 * char[]} into a byte array that {@link ProcessExecution} zeroes after writing.
 */
public final class PtyShellRunner implements ShellRunner {

  private static final Logger log = LoggerFactory.getLogger(PtyShellRunner.class);

  private final SudoPasswordProvider sudoPasswordProvider;

  public PtyShellRunner(SudoPasswordProvider sudoPasswordProvider) {
    this.sudoPasswordProvider = sudoPasswordProvider;
  }

  @Override
  public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
    return run(command, env, timeout, ExecutionOutput.sink());
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Duration timeout,
      Consumer<String> outputSink) {
    log.debug("Interactive executing: {}", firstArgument(command));
    ProcessExecution.Request request =
        ProcessExecution.Request.of(commandWithSudoStdin(command), env, timeout)
            .withOutputSink(outputSink);
    return ProcessExecution.run(requiresSudo(command) ? request.withStdin(sudoStdin()) : request);
  }

  private boolean requiresSudo(List<String> command) {
    return !command.isEmpty() && "sudo".equals(command.getFirst());
  }

  private List<String> commandWithSudoStdin(List<String> command) {
    if (!requiresSudo(command)) {
      return command;
    }
    List<String> updated = new ArrayList<>();
    updated.add("sudo");
    updated.add("-S");
    updated.add("-p");
    updated.add("[sudo] password:");
    updated.addAll(command.subList(1, command.size()));
    return List.copyOf(updated);
  }

  private byte[] sudoStdin() {
    Optional<char[]> password = sudoPasswordProvider.requestPassword("[sudo] password");
    if (password.isEmpty()) {
      return new byte[] {'\n'};
    }
    char[] characters = password.orElseThrow();
    try {
      return encodeWithNewline(characters);
    } finally {
      Arrays.fill(characters, '\0');
    }
  }

  private byte[] encodeWithNewline(char[] characters) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(characters));
    try {
      byte[] bytes = new byte[encoded.remaining() + 1];
      encoded.get(bytes, 0, bytes.length - 1);
      bytes[bytes.length - 1] = '\n';
      return bytes;
    } finally {
      if (encoded.hasArray()) {
        Arrays.fill(encoded.array(), (byte) 0);
      }
    }
  }

  private String firstArgument(List<String> command) {
    return command.isEmpty() ? "<empty command>" : command.getFirst();
  }
}
