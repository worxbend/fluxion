package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.SourceDocument;
import dev.sysboot.config.yaml.contract.SourceSpecDocument;
import dev.sysboot.config.yaml.contract.SourcesDocument;
import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

final class WorkstationProfileSourceMapper {

  SourceMapping map(Optional<SourcesDocument> sources, List<PlanEntryDocument> selectedPlan) {
    if (sources.isEmpty()) {
      return new SourceMapping(List.of(), List.of());
    }
    Set<PackageManagerKind> selectedManagers = selectedManagers(selectedPlan);
    return mapSources(sources.orElseThrow(), selectedManagers);
  }

  private SourceMapping mapSources(SourcesDocument sources, Set<PackageManagerKind> managers) {
    var setups = new ArrayList<SourceSetup>();
    var skipped = new ArrayList<SkippedPlanEntry>();
    mapSection("apt", PackageManagerKind.APT, sources.apt(), managers, this::apt, setups, skipped);
    mapSection("dnf", PackageManagerKind.DNF, sources.dnf(), managers, this::rpm, setups, skipped);
    mapSection("rpm", PackageManagerKind.DNF, sources.rpm(), managers, this::rpm, setups, skipped);
    mapSection(
        "zypper",
        PackageManagerKind.ZYPPER,
        sources.zypper(),
        managers,
        this::zypper,
        setups,
        skipped);
    mapSection(
        "flatpak",
        PackageManagerKind.FLATPAK,
        sources.flatpak(),
        managers,
        this::flatpak,
        setups,
        skipped);
    return new SourceMapping(setups, skipped);
  }

  private void mapSection(
      String section,
      PackageManagerKind manager,
      List<SourceDocument> sources,
      Set<PackageManagerKind> selectedManagers,
      Function<SourceDocument, SourceSetup> mapper,
      List<SourceSetup> setups,
      List<SkippedPlanEntry> skipped) {
    for (SourceDocument source : sources) {
      if (selectedManagers.contains(manager)) {
        setups.add(mapper.apply(source));
      } else {
        skipped.add(skippedSource(source, section));
      }
    }
  }

  private SkippedPlanEntry skippedSource(SourceDocument source, String section) {
    return new SkippedPlanEntry(
        source.name().orElse("<unnamed>"),
        section + "-source",
        "source section " + section + " is not relevant to selected host package managers");
  }

  private Set<PackageManagerKind> selectedManagers(List<PlanEntryDocument> selectedPlan) {
    var managers = EnumSet.noneOf(PackageManagerKind.class);
    selectedPlan.stream().map(this::managerFor).flatMap(Optional::stream).forEach(managers::add);
    return managers;
  }

  private Optional<PackageManagerKind> managerFor(PlanEntryDocument entry) {
    return entry.kind().map(this::managerForKind).orElseGet(Optional::empty);
  }

  private Optional<PackageManagerKind> managerForKind(String rawKind) {
    return switch (rawKind.strip().toLowerCase(Locale.ROOT)) {
      case "apt-packages" -> Optional.of(PackageManagerKind.APT);
      case "dnf-packages" -> Optional.of(PackageManagerKind.DNF);
      case "pacman-packages" -> Optional.of(PackageManagerKind.PACMAN);
      case "zypper-packages" -> Optional.of(PackageManagerKind.ZYPPER);
      case "flatpak-packages" -> Optional.of(PackageManagerKind.FLATPAK);
      default -> Optional.empty();
    };
  }

  private AptRepositorySourceSetup apt(SourceDocument source) {
    SourceSpecDocument spec = source.spec().orElseThrow();
    String name = source.name().orElseThrow();
    Optional<URI> signingKeyUrl = spec.signingKeyUrl().map(URI::create);
    Optional<Path> keyringPath =
        spec.keyring()
            .map(Path::of)
            .or(() -> signingKeyUrl.map(ignored -> Path.of("/etc/apt/keyrings/" + name + ".gpg")));
    return new AptRepositorySourceSetup(
        new ModuleName(name),
        spec.source().orElseThrow(),
        spec.sourceList()
            .map(Path::of)
            .orElse(Path.of("/etc/apt/sources.list.d/" + name + ".list")),
        signingKeyUrl,
        keyringPath);
  }

  private RpmRepositorySourceSetup rpm(SourceDocument source) {
    SourceSpecDocument spec = source.spec().orElseThrow();
    String name = source.name().orElseThrow();
    return new RpmRepositorySourceSetup(
        new ModuleName(name),
        spec.id().orElseThrow(),
        URI.create(spec.baseUrl().orElseThrow()),
        spec.repoFile().map(Path::of).orElse(Path.of("/etc/yum.repos.d/" + name + ".repo")),
        spec.gpgKeyUrl().map(URI::create),
        spec.enabled().orElse(true),
        spec.gpgCheck().orElse(true));
  }

  private ZypperRepositorySourceSetup zypper(SourceDocument source) {
    SourceSpecDocument spec = source.spec().orElseThrow();
    String name = source.name().orElseThrow();
    return new ZypperRepositorySourceSetup(
        new ModuleName(name),
        spec.id().orElseThrow(),
        URI.create(spec.baseUrl().orElseThrow()),
        spec.repoFile().map(Path::of).orElse(Path.of("/etc/zypp/repos.d/" + name + ".repo")),
        spec.gpgKeyUrl().map(URI::create),
        spec.enabled().orElse(true),
        spec.gpgCheck().orElse(true));
  }

  private FlatpakRemoteSourceSetup flatpak(SourceDocument source) {
    SourceSpecDocument spec = source.spec().orElseThrow();
    return new FlatpakRemoteSourceSetup(
        new ModuleName(source.name().orElseThrow()),
        spec.remote().orElseThrow(),
        URI.create(spec.url().orElseThrow()),
        spec.system().orElse(true));
  }

  record SourceMapping(List<SourceSetup> sourceSetups, List<SkippedPlanEntry> skippedEntries) {
    SourceMapping {
      sourceSetups = List.copyOf(sourceSetups);
      skippedEntries = List.copyOf(skippedEntries);
    }
  }
}
