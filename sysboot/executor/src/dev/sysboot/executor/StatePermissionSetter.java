package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@FunctionalInterface
interface StatePermissionSetter {

  void set(Path path, Set<PosixFilePermission> permissions) throws IOException;
}
