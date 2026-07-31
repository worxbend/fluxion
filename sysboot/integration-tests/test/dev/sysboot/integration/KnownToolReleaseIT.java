package dev.sysboot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ToolSpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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

  private static final int MAX_ASSET_BYTES = 64 * 1024 * 1024;

  @Test
  @Timeout(120)
  void everyKnownToolPublishesEveryCataloguedAsset() {
    assumeTrue(hasNetwork(), "no network access; skipping upstream release check");

    for (ToolSpec spec : KnownTools.all()) {
      for (String assetName : spec.assetSha256().keySet()) {
        String url = spec.assetUrl(assetName);
        assertThat(statusOf(url)).as("%s expects %s", spec.name(), url).isBetween(200, 299);
      }
    }
  }

  @Test
  @Timeout(120)
  void everyKnownToolPublishesTheChecksumsFluxionVerifiesAgainst() {
    assumeTrue(hasNetwork(), "no network access; skipping upstream checksum check");

    for (ToolSpec spec : KnownTools.all()) {
      if (spec.checksumPolicy() == ToolSpec.ChecksumPolicy.CHECKSUMS_FILE) {
        assertSuccessful(spec, spec.releaseDownloadBase() + "/checksums.txt");
      } else if (spec.checksumPolicy() == ToolSpec.ChecksumPolicy.SIDECAR_SHA256) {
        for (String assetName : spec.assetSha256().keySet()) {
          assertSuccessful(spec, spec.assetUrl(assetName) + ".sha256");
        }
      }
    }
  }

  @Test
  @Timeout(300)
  void everyCataloguedDigestMatchesThePublishedAssetBytes() {
    boolean required = Boolean.parseBoolean(System.getenv("SYSBOOT_VERIFY_KNOWN_TOOL_BYTES"));
    assumeTrue(required, "set SYSBOOT_VERIFY_KNOWN_TOOL_BYTES=true to verify all release bytes");
    assertThat(hasNetwork()).as("required trusted-tool byte verification has network").isTrue();

    for (ToolSpec spec : KnownTools.all()) {
      spec.assetSha256()
          .forEach(
              (assetName, expected) ->
                  assertThat(sha256OfBoundedDownload(spec.assetUrl(assetName)))
                      .as("%s catalog digest for %s", spec.name(), assetName)
                      .isEqualToIgnoringCase(expected));
    }
  }

  private static void assertSuccessful(ToolSpec spec, String url) {
    assertThat(statusOf(url))
        .as("%s expects checksums at %s", spec.name(), url)
        .isBetween(200, 299);
  }

  private static String sha256OfBoundedDownload(String url) {
    var request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
    try {
      HttpResponse<InputStream> response =
          CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
      assertThat(response.statusCode()).as(url).isBetween(200, 299);
      try (InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(MAX_ASSET_BYTES + 1);
        assertThat(bytes.length)
            .as("%s stays within the integration-test bound", url)
            .isLessThanOrEqualTo(MAX_ASSET_BYTES);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      }
    } catch (IOException e) {
      throw new AssertionError("Failed to download " + url, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted downloading " + url, e);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("Java platform lacks SHA-256", e);
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
