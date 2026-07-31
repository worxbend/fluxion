package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.PackageManagerKind;
import java.nio.file.Path;
import java.util.Optional;

final class WorkstationPlanMappers {

  private final WorkstationPackageModuleMapper packages;
  private final WorkstationStructuredModuleMapper structured;
  private final WorkstationSystemToolModuleMapper systemTool;

  WorkstationPlanMappers(
      WorkstationProfileWhenEvaluator whenEvaluator,
      WorkstationMappingSupport support,
      Path manifestDirectory) {
    this.packages = new WorkstationPackageModuleMapper(support);
    this.structured =
        new WorkstationStructuredModuleMapper(whenEvaluator, support, manifestDirectory);
    this.systemTool = new WorkstationSystemToolModuleMapper(support);
  }

  BootstrapModule packageModule(
      PlanEntryDocument entry, PackageManagerKind kind, BootstrapPolicy policy) {
    return packages.packageModule(entry, kind, policy);
  }

  PackageManagerKind aurPackageManager(PlanEntryDocument entry) {
    return packages.aurPackageManager(entry);
  }

  BootstrapModule sdkmanModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return packages.sdkmanModule(entry, policy);
  }

  BootstrapModule flatpakModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return packages.flatpakModule(entry, policy);
  }

  BootstrapModule compiledBinaryModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return packages.compiledBinaryModule(entry, policy);
  }

  BootstrapModule shellScriptModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return structured.shellScriptModule(entry, policy);
  }

  BootstrapModule shellCommandModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return structured.shellCommandModule(entry, policy);
  }

  Optional<BootstrapModule> fileWriteModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return structured.fileWriteModule(entry, policy);
  }

  BootstrapModule nerdFontModule(PlanEntryDocument entry) {
    return systemTool.nerdFontModule(entry);
  }

  BootstrapModule binstallerModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.binstallerModule(entry, policy);
  }

  BootstrapModule userGroupsModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.userGroupsModule(entry, policy);
  }

  BootstrapModule gitConfigModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.gitConfigModule(entry, policy);
  }

  BootstrapModule gitRepoModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.gitRepoModule(entry, policy);
  }

  BootstrapModule systemdUnitModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.systemdUnitModule(entry, policy);
  }

  BootstrapModule systemSettingModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.systemSettingModule(entry, policy);
  }

  BootstrapModule systemUpdateModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.systemUpdateModule(entry, policy);
  }

  BootstrapModule gpgKeyModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.gpgKeyModule(entry, policy);
  }

  BootstrapModule toolPackagesModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return systemTool.toolPackagesModule(entry, policy);
  }

  BootstrapModule zypperRepositoryModule(PlanEntryDocument entry) {
    return systemTool.zypperRepositoryModule(entry);
  }

  BootstrapModule dotbotModule(PlanEntryDocument entry) {
    return systemTool.dotbotModule(entry);
  }

  BootstrapModule interruptModule(PlanEntryDocument entry) {
    return systemTool.interruptModule(entry);
  }
}
