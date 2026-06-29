package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShellScriptExecutorTest {

  @Test
  void execute_whenScriptUsesUrl_downloadsThenRunsTempScript() throws Exception {
    var runner = new FakeShellRunner();
    var downloader = new FakeDownloadClient("#!/bin/bash\necho ok\n");
    var item =
        new ShellScriptItem(
            "remote-script",
            Optional.empty(),
            Optional.of(new URI("https://example.test/install.sh")),
            List.of("--dry"),
            Optional.empty(),
            List.of(),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofSeconds(5));

    StepResult result = new ShellScriptExecutor(runner, downloader).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(downloader.urls).containsExactly(new URI("https://example.test/install.sh"));
    assertThat(runner.commands).hasSize(1);
    assertThat(runner.commands.getFirst()).startsWith("/bin/bash");
    assertThat(runner.commands.getFirst()).endsWith("--dry");
  }

  private ShellScriptModule module(ShellScriptItem item) {
    return new ShellScriptModule(
        new ModuleName("scripts"), List.of(item), Optional.empty(), false, Optional.empty());
  }

  private static final class FakeShellRunner implements dev.sysboot.core.ShellRunner {
    private final ArrayList<List<String>> commands = new ArrayList<>();

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(command);
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }

  private static final class FakeDownloadClient implements BinaryDownloadClient {
    private final String content;
    private final ArrayList<URI> urls = new ArrayList<>();

    private FakeDownloadClient(String content) {
      this.content = content;
    }

    @Override
    public void downloadToFile(URI url, Path destination) throws IOException {
      urls.add(url);
      Files.writeString(destination, content);
    }

    @Override
    public String downloadText(URI url) {
      return content;
    }
  }
}
