package dev.sysboot.config;

import static dev.sysboot.config.MappingSupport.expandHome;
import static dev.sysboot.config.MappingSupport.requireField;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.WorkstationChecksumDocument;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.Sha256Digest;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

final class WorkstationMappingSupport {

  String planName(PlanEntryDocument entry) {
    return requireField(entry.name().orElse(null), "spec.plan[].name");
  }

  boolean continueOnError(PlanEntryDocument entry, BootstrapPolicy policy) {
    return entry
        .execution()
        .flatMap(execution -> execution.continueOnError())
        .or(policy::continueOnErrorDefault)
        .orElse(true);
  }

  Optional<Checksum> checksum(Optional<WorkstationChecksumDocument> dto) {
    return dto.map(
        value -> new Checksum(value.algorithm().orElseThrow(), value.value().orElseThrow()));
  }

  Optional<Sha256Digest> sourceSha256(Optional<WorkstationChecksumDocument> dto) {
    Optional<Checksum> checksum = checksum(dto);
    checksum
        .filter(value -> !value.hasValidSha256Value())
        .ifPresent(
            ignored -> {
              throw new IllegalArgumentException("source checksum must be valid SHA-256");
            });
    return checksum.map(value -> new Sha256Digest(value.value()));
  }

  BinaryUrl binaryUrl(String rawUrl) {
    return new BinaryUrl(URI.create(rawUrl));
  }

  Path absolutePath(String rawPath) {
    Path path = Path.of(expandHome(rawPath));
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Required field 'installPath' must be absolute");
    }
    return path;
  }
}
