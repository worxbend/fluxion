package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.KnownTools;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolCacheTest {

  @TempDir Path tempDirectory;

  @Test
  void executableVersionAndLockPathsRemainInsideNormalizedCacheRoot() {
    Path configured = tempDirectory.resolve("nested/../tools");
    var cache = new ToolCache(configured);

    assertThat(cache.versionDir(KnownTools.DOTBOT_GO).startsWith(cache.baseDir())).isTrue();
    assertThat(cache.executable(KnownTools.DOTBOT_GO).startsWith(cache.baseDir())).isTrue();
    assertThat(cache.installLock(KnownTools.DOTBOT_GO).startsWith(cache.baseDir())).isTrue();
    assertThat(cache.baseDir()).isEqualTo(configured.normalize());
  }

  @Test
  void confinementRejectsEscapeBeforeCreatingAnyCacheDirectories() {
    Path cacheRoot = tempDirectory.resolve("tools");
    var cache = new ToolCache(cacheRoot);
    Path outside = tempDirectory.resolve("outside");

    assertThatThrownBy(() -> cache.requireConfined(outside))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("escapes");
    assertThat(cacheRoot).doesNotExist();
    assertThat(outside).doesNotExist();
  }
}
