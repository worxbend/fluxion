package dev.sysboot.core;

public sealed interface SourceSetup
    permits AptRepositorySourceSetup,
        RpmRepositorySourceSetup,
        ZypperRepositorySourceSetup,
        PacmanRepositorySourceSetup,
        FlatpakRemoteSourceSetup {

  ModuleName name();

  PackageManagerKind packageManager();
}
