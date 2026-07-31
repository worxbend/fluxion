package dev.sysboot.config;

import static dev.sysboot.config.MappingSupport.requireField;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.SdkmanPackage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class WorkstationPackageModuleMapper {

  private final WorkstationMappingSupport support;

  WorkstationPackageModuleMapper(WorkstationMappingSupport support) {
    this.support = support;
  }

  PackageModule packageModule(
      PlanEntryDocument entry, PackageManagerKind kind, BootstrapPolicy policy) {
    PlanSpecDocument spec =
        requireField(entry.spec().orElse(null), support.planName(entry) + ".spec");
    return new PackageModule(
        new ModuleName(support.planName(entry)),
        kind,
        spec.packages().stream().map(PackageName::new).toList(),
        packageActions(spec),
        support.continueOnError(entry, policy));
  }

  PackageManagerKind aurPackageManager(PlanEntryDocument entry) {
    PlanSpecDocument spec =
        requireField(entry.spec().orElse(null), support.planName(entry) + ".spec");
    String packageManager = spec.packageManager().orElseThrow().strip().toLowerCase(Locale.ROOT);
    return switch (packageManager) {
      case "paru" -> PackageManagerKind.PARU;
      case "yay" -> PackageManagerKind.YAY;
      default -> throw new IllegalArgumentException("Unsupported AUR helper: " + packageManager);
    };
  }

  SdkmanModule sdkmanModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec =
        requireField(entry.spec().orElse(null), support.planName(entry) + ".spec");
    return new SdkmanModule(
        new ModuleName(support.planName(entry)),
        spec.packageItems().stream().map(this::sdkmanPackage).toList(),
        support.continueOnError(entry, policy));
  }

  FlatpakModule flatpakModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec =
        requireField(entry.spec().orElse(null), support.planName(entry) + ".spec");
    var appIds = new ArrayList<String>();
    appIds.addAll(spec.apps());
    appIds.addAll(spec.appIds());
    return new FlatpakModule(
        new ModuleName(support.planName(entry)),
        spec.remote().orElse("flathub"),
        List.copyOf(appIds),
        support.continueOnError(entry, policy));
  }

  CompiledBinaryModule compiledBinaryModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    String name = support.planName(entry);
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), name + ".spec");
    return new CompiledBinaryModule(
        new ModuleName(name),
        requireField(spec.binaryName().orElse(null), name + ".spec.binaryName"),
        support.binaryUrl(requireField(spec.url().orElse(null), name + ".spec.url")),
        support.checksum(spec.checksum()),
        spec.checksumUrl().map(support::binaryUrl),
        spec.signatureUrl().map(support::binaryUrl),
        support.absolutePath(
            requireField(spec.installPath().orElse(null), name + ".spec.installPath")),
        spec.archivePath(),
        spec.stripComponents().orElse(0),
        Optional.of(spec.installMode().orElse("0755")),
        spec.symlinkPath().map(support::absolutePath),
        support.continueOnError(entry, policy),
        spec.versionCommand(),
        spec.expectedVersion(),
        spec.allowedSignerFingerprint());
  }

  private List<PackageManagerAction> packageActions(PlanSpecDocument spec) {
    return spec.actions().stream()
        .map(
            action ->
                new PackageManagerAction(
                    action.action().orElseThrow().toLowerCase(Locale.ROOT), action.args()))
        .toList();
  }

  private SdkmanPackage sdkmanPackage(JsonNode node) {
    if (node.isTextual()) {
      return new SdkmanPackage(node.asText());
    }
    return new SdkmanPackage(text(node, "candidate").orElseThrow(), text(node, "version"));
  }

  private Optional<String> text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isTextual() || value.asText().isBlank()
        ? Optional.empty()
        : Optional.of(value.asText());
  }
}
