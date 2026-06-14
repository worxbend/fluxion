package dev.sysboot.core;

public sealed interface OsTarget
    permits OsTarget.FedoraTarget,
        OsTarget.ArchTarget,
        OsTarget.OpenSuseTarget,
        OsTarget.DebianTarget {

  record FedoraTarget(String release) implements OsTarget {}

  record ArchTarget() implements OsTarget {}

  record OpenSuseTarget(String version) implements OsTarget {}

  record DebianTarget(String codename) implements OsTarget {}
}
