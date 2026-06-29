package dev.sysboot.core;

public sealed interface BootstrapModule
    permits PackageModule,
        AptRepositoryModule,
        RpmRepositoryModule,
        PacmanRepositoryModule,
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
        SdkmanModule {

  ModuleName name();
}
