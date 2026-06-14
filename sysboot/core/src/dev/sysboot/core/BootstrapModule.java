package dev.sysboot.core;

public sealed interface BootstrapModule
    permits PackageModule,
        FlatpakModule,
        ShellScriptModule,
        CompiledBinaryModule,
        ZypperModule,
        DotbotModule,
        DefaultShellModule,
        OhMyZshModule,
        ToolchainModule,
        NerdFontModule,
        ShellReloadModule,
        ShellCommandModule {

  ModuleName name();
}
