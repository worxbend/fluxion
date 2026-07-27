package dev.sysboot.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Installs Nerd Font families through <a href="https://github.com/worxbend/nerd-fonts-installer">
 * nerd-fonts-installer</a>.
 *
 * <p>Fluxion does not download font archives itself; it hands the installer a config and reports
 * what it did.
 */
public final class NerdFontExecutor {

  private static final Duration FONT_INSTALL_TIMEOUT = Duration.ofMinutes(15);
  private static final String ITEM = "nerd-fonts";

  private final ShellRunner shellRunner;
  private final ObjectMapper yamlMapper;
  private final ToolResolver toolResolver;

  public NerdFontExecutor(ShellRunner shellRunner) {
    this(shellRunner, new BrokeredToolResolver(new ToolBroker()));
  }

  NerdFontExecutor(ShellRunner shellRunner, ToolResolver toolResolver) {
    this.shellRunner = shellRunner;
    this.yamlMapper = new ObjectMapper(new YAMLFactory());
    this.toolResolver = toolResolver;
  }

  public StepResult execute(NerdFontModule module) {
    Path generatedConfig = null;
    try {
      Path configPath = resolveConfig(module);
      generatedConfig = module.configPath().isPresent() ? null : configPath;
      Path installer = toolResolver.resolve(toolSpec(module));
      var command = List.of(installer.toString(), "--config", configPath.toString());
      ProcessResult result = shellRunner.run(command, Map.of(), FONT_INSTALL_TIMEOUT);

      return result.isSuccess()
          ? new StepResult.Success(ITEM, result.elapsed())
          : new StepResult.Failure(
              ITEM, failureMessage(result), result.exitCode(), result.elapsed());
    } catch (IOException | ToolResolutionException e) {
      return new StepResult.Failure(
          ITEM, "Failed to prepare Nerd Font installer: " + e.getMessage(), 1, Duration.ZERO);
    } finally {
      deleteIfExists(generatedConfig);
    }
  }

  /** Command preview used by {@code plan} and {@code dry-run}. */
  public List<String> commandPreview(NerdFontModule module) {
    String config = module.configPath().map(Path::toString).orElse("<generated from profile>");
    return List.of(module.nerdfontBinary(), "--config", config, "--dry-run");
  }

  static ToolSpec toolSpec(NerdFontModule module) {
    ToolSpec base = KnownTools.NERD_FONTS_INSTALLER;
    return new ToolSpec(
        base.name(),
        base.repository(),
        module.installerVersion(),
        base.assetTemplates(),
        base.osNaming(),
        base.checksumPolicy(),
        Optional.of(module.nerdfontBinary()));
  }

  private Path resolveConfig(NerdFontModule module) throws IOException {
    Optional<Path> declared = module.configPath();
    if (declared.isEmpty()) {
      return writeTempConfig(module);
    }
    Path path = declared.orElseThrow();
    if (!Files.isReadable(path)) {
      throw new IOException("Nerd Fonts config not readable: " + path);
    }
    return path;
  }

  private String failureMessage(ProcessResult result) {
    String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
    return detail.isBlank()
        ? "nerd-fonts-installer exited with code " + result.exitCode()
        : detail.strip();
  }

  private Path writeTempConfig(NerdFontModule module) throws IOException {
    var cfg = module.config();
    var dto = new LinkedHashMap<String, Object>();
    dto.put("release", cfg.release());
    dto.put("destination", cfg.destination().toString());
    dto.put("refresh_font_cache", cfg.refreshFontCache());
    dto.put("families", cfg.families());
    Path tmp = Files.createTempFile("fluxion-nerdfonts-", ".yaml");
    yamlMapper.writeValue(tmp.toFile(), dto);
    return tmp;
  }

  private void deleteIfExists(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // A leftover temp config is not worth failing the step over.
    }
  }
}
