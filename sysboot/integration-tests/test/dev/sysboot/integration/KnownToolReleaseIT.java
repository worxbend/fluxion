package dev.sysboot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sysboot.core.HostPlatform;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ToolSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Checks that the release assets Fluxion expects actually exist upstream.
 *
 * <p>A wrong repository name or asset template is invisible to unit tests — the stubs happily
 * resolve — and only shows up as a 404 on a user's machine. That failure mode has happened before,
 * so it gets a test that talks to the real releases. Skipped automatically when there is no
 * network, so it never makes an offline build fail.
 */
class KnownToolReleaseIT {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private static final HostPlatform PLATFORM = HostPlatform.detect();

  @Test
  @Timeout(120)
  void everyKnownToolPublishesTheAssetFluxionAsksFor() {
    assumeTrue(hasNetwork(), "no network access; skipping upstream release check");

    for (ToolSpec spec : KnownTools.all()) {
      String url = spec.assetUrl(PLATFORM);
      assertThat(statusOf(url)).as("%s expects %s", spec.name(), url).isBetween(200, 299);
    }
  }

  @Test
  @Timeout(120)
  void everyKnownToolPublishesTheChecksumsFluxionVerifiesAgainst() {
    assumeTrue(hasNetwork(), "no network access; skipping upstream checksum check");

    for (ToolSpec spec : KnownTools.all()) {
      String url =
          switch (spec.checksumPolicy()) {
            case NONE -> null;
            case SIDECAR_SHA256 -> spec.assetUrl(PLATFORM) + ".sha256";
            case CHECKSUMS_FILE -> spec.releaseDownloadBase() + "/checksums.txt";
          };
      if (url == null) {
        continue;
      }
      assertThat(statusOf(url))
          .as("%s expects checksums at %s", spec.name(), url)
          .isBetween(200, 299);
    }
  }

  private static int statusOf(String url) {
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private static boolean hasNetwork() {
    return statusOf("https://github.com") > 0;
  }
}
