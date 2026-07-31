package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import java.util.List;

final class WorkstationPlanValidators {

  private final WorkstationPackageFileValidator packageFile;
  private final WorkstationStructuredCommandValidator structured;
  private final WorkstationToolingTrustValidator toolingTrust;
  private final WorkstationSystemControlValidator systemControl;

  WorkstationPlanValidators(WorkstationValidationSupport support) {
    this.packageFile = new WorkstationPackageFileValidator(support);
    this.structured = new WorkstationStructuredCommandValidator(support);
    this.toolingTrust = new WorkstationToolingTrustValidator(support);
    this.systemControl = new WorkstationSystemControlValidator(support);
  }

  WorkstationPackageFileValidator packageFile() {
    return packageFile;
  }

  void validateAurPackageManager(String path, PlanSpecDocument spec, List<String> errors) {
    packageFile.validateAurPackageManager(path, spec, errors);
  }

  void validateInterruptSpec(String path, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateInterruptSpec(path, spec, errors);
  }

  void validateBinarySpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    packageFile.validateBinarySpec(path, name, spec, errors);
  }

  void validateScriptSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    structured.validateScriptSpec(path, name, spec, errors);
  }

  void validateCommandSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    structured.validateCommandSpec(path, name, spec, errors);
  }

  void validateFileWriteSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    packageFile.validateFileWriteSpec(path, name, spec, errors);
  }

  void validateNerdFontSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    toolingTrust.validateNerdFontSpec(path, name, spec, errors);
  }

  void validateDotfilesSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    toolingTrust.validateDotfilesSpec(path, name, spec, errors);
  }

  void validateBinstallerSpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    toolingTrust.validateBinstallerSpec(path, name, spec, errors);
  }

  void validateUserGroupsSpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateUserGroupsSpec(path, name, spec, errors);
  }

  void validateGitConfigSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateGitConfigSpec(path, name, spec, errors);
  }

  void validateGitRepoSpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateGitRepoSpec(path, name, spec, errors);
  }

  void validateSystemdUnitSpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateSystemdUnitSpec(path, name, spec, errors);
  }

  void validateSystemSettingSpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateSystemSettingSpec(path, name, spec, errors);
  }

  void validateSystemUpdateSpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    systemControl.validateSystemUpdateSpec(path, name, spec, errors);
  }

  void validateGpgKeySpec(String path, String name, PlanSpecDocument spec, List<String> errors) {
    toolingTrust.validateGpgKeySpec(path, name, spec, errors);
  }

  void validateToolPackagesSpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    toolingTrust.validateToolPackagesSpec(path, name, spec, errors);
  }

  void validateZypperRepositorySpec(
      String path, String name, PlanSpecDocument spec, List<String> errors) {
    toolingTrust.validateZypperRepositorySpec(path, name, spec, errors);
  }
}
