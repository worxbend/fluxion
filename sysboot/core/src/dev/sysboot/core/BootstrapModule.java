package dev.sysboot.core;

public sealed interface BootstrapModule
    permits PackageModule,
        AptRepositoryModule,
        RpmRepositoryModule,
        PacmanRepositoryModule,
        FileWriteModule,
        FlatpakModule,
        FlatpakRemoteModule,
        ShellScriptModule,
        CompiledBinaryModule,
        ZypperModule,
        DotbotModule,
        DefaultShellModule,
        OhMyZshModule,
        ToolchainModule,
        NerdFontModule,
        ShellReloadModule,
        ShellCommandModule,
        AssertModule,
        ManualModule,
        InterruptModule,
        SdkmanModule,
        BinstallerModule,
        UserGroupsModule,
        GitConfigModule,
        GitRepoModule,
        SystemdUnitModule,
        SystemSettingModule,
        SystemUpdateModule,
        GpgKeyModule,
        ToolPackagesModule {

  ModuleName name();
}
