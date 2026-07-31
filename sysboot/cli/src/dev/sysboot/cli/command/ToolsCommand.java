package dev.sysboot.cli.command;

import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.HostPlatform;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ToolSpec;
import dev.sysboot.executor.ToolBroker;
import dev.sysboot.executor.ToolResolutionException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Reports on and obtains the external tools Fluxion delegates to.
 *
 * <p>Fluxion orchestrates rather than reimplements: dotfiles go to {@code dotbot}, fonts to {@code
 * nerd-fonts-installer}, binary distributions to {@code binstaller}. That makes "which of those do
 * I have, and where would the missing ones come from?" a question a user needs answered before a
 * run rather than during one.
 */
@Command(
    name = "tools",
    description = "Inspect and install the external tools Fluxion delegates to",
    subcommands = {ToolsCommand.ListSubcommand.class, ToolsCommand.InstallSubcommand.class})
public final class ToolsCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().getOut().println("Run 'fluxion tools --help' for subcommands.");
  }

  /**
   * Tools are selected by the short name of their GitHub repository, not by executable name: two
   * catalog entries both install a binary called {@code dotbot}, so {@code dotbot} alone would be
   * ambiguous while {@code dotbot-go} and {@code dotbot-scala} are not.
   */
  static String selector(ToolSpec tool) {
    String repository = tool.repository();
    int slash = repository.lastIndexOf('/');
    return slash < 0 ? repository : repository.substring(slash + 1);
  }

  static Optional<ToolSpec> find(String selector) {
    return KnownTools.all().stream()
        .filter(tool -> selector(tool).equalsIgnoreCase(selector))
        .findFirst();
  }

  static String selectors() {
    return KnownTools.all().stream()
        .map(ToolsCommand::selector)
        .sorted()
        .reduce("", (left, right) -> left.isEmpty() ? right : left + ", " + right);
  }

  @Command(
      name = "list",
      description = "Show every delegated tool, where it would come from, and its pinned version")
  public static final class ListSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Option(
        names = "--format",
        defaultValue = "text",
        description = "Output format: ${COMPLETION-CANDIDATES}")
    private OutputFormat format;

    @Override
    public void run() {
      var broker = new ToolBroker();
      HostPlatform platform = HostPlatform.detect();
      List<ToolBroker.Resolution> resolutions =
          KnownTools.all().stream().map(broker::describe).toList();
      PrintWriter out = spec.commandLine().getOut();

      if (format == OutputFormat.JSON) {
        // LinkedHashMap, not Map.of: Map.of randomises iteration order per JVM run, which would
        // make this command's output differ between invocations for no reason.
        var document = new LinkedHashMap<String, Object>();
        document.put("platform", platform.toString());
        document.put(
            "tools", resolutions.stream().map(resolution -> json(resolution, platform)).toList());
        JsonOutput.write(out, document);
        return;
      }

      String row =
          "%%-%ds  %%-%ds  %%-%ds  %%s%%n"
              .formatted(
                  width(resolutions, "TOOL", resolution -> selector(resolution.spec())),
                  width(resolutions, "BINARY", resolution -> resolution.spec().executableName()),
                  width(resolutions, "VERSION", resolution -> resolution.spec().version()));

      out.printf("Platform: %s%n%n", platform);
      out.printf(row, "TOOL", "BINARY", "VERSION", "SOURCE");
      for (ToolBroker.Resolution resolution : resolutions) {
        ToolSpec tool = resolution.spec();
        out.printf(
            row,
            selector(tool),
            tool.executableName(),
            tool.version(),
            describeSource(resolution, platform));
      }
      out.println();
      out.println("Tools already on PATH are used as-is; Fluxion never replaces them.");
      out.println("Run 'fluxion tools install <tool>' to fetch a missing one into the cache.");
    }

    private int width(
        List<ToolBroker.Resolution> resolutions,
        String header,
        java.util.function.Function<ToolBroker.Resolution, String> cell) {
      return resolutions.stream()
          .mapToInt(resolution -> cell.apply(resolution).length())
          .max()
          .stream()
          .map(longest -> Math.max(longest, header.length()))
          .findFirst()
          .orElse(header.length());
    }

    private String describeSource(ToolBroker.Resolution resolution, HostPlatform platform) {
      return switch (resolution.source()) {
        case PATH -> "on PATH: " + resolution.path();
        case CACHE -> "cached: " + resolution.path();
        case DOWNLOAD -> "missing, would download " + resolution.spec().assetUrl(platform);
      };
    }

    private Map<String, Object> json(ToolBroker.Resolution resolution, HostPlatform platform) {
      ToolSpec tool = resolution.spec();
      var output = new LinkedHashMap<String, Object>();
      output.put("tool", selector(tool));
      output.put("executable", tool.executableName());
      output.put("repository", tool.repository());
      output.put("version", tool.version());
      output.put("source", resolution.source().name().toLowerCase(java.util.Locale.ROOT));
      output.put("path", resolution.path().toString());
      output.put("checksumPolicy", tool.checksumPolicy().name());
      output.put("assetCandidates", tool.assetNames(platform));
      return output;
    }
  }

  @Command(
      name = "install",
      description = "Download and verify a delegated tool into the Fluxion tool cache")
  public static final class InstallSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<tool>", description = "Tool to install")
    private String selector;

    @Option(
        names = "--release",
        description = "Release tag to install instead of the pinned default")
    private String version;

    @Override
    public void run() {
      ToolSpec pinned =
          find(selector)
              .orElseThrow(
                  () ->
                      new CliFailureException(
                          ExitCode.INVALID_INPUT,
                          "Unknown tool '" + selector + "'. Known tools: " + selectors()));
      ToolSpec tool;
      try {
        tool = version == null ? pinned : pinned.withVersion(version);
      } catch (IllegalArgumentException e) {
        throw new CliFailureException(
            ExitCode.INVALID_INPUT, "Invalid tool version: " + e.getMessage(), e);
      }

      PrintWriter out = spec.commandLine().getOut();
      var broker = new ToolBroker();
      ToolBroker.Resolution existing = broker.describe(tool);
      if (existing.source() == ToolBroker.Source.PATH) {
        out.printf(
            "%s is already on PATH at %s; Fluxion will use it and installs nothing.%n",
            selector(tool), existing.path());
        return;
      }

      try {
        Path installed = broker.resolve(tool);
        out.printf("%s %s installed at %s%n", selector(tool), tool.version(), installed);
      } catch (ToolResolutionException e) {
        throw new CliFailureException(
            ExitCode.EXTERNAL_DEPENDENCY_ERROR, e.getMessage() == null ? "" : e.getMessage(), e);
      }
    }
  }
}
