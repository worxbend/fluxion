package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkstationProfileDocumentTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

  @Test
  void readValue_whenMinimalWorkstationProfile_deserializesMetadataAndEmptySpec()
      throws IOException, URISyntaxException {
    Path fixture =
        Path.of(
            getClass()
                .getResource("/minimal-workstation-profile.yaml")
                .toURI());

    var document = objectMapper.readValue(fixture.toFile(), WorkstationProfileDocument.class);

    assertThat(document.apiVersion()).contains("initkit.io/v1alpha1");
    assertThat(document.kind()).contains("WorkstationProfile");
    assertThat(document.metadata())
        .flatMap(metadata -> metadata.name())
        .contains("developer-workstation");
    assertThat(document.spec()).hasValueSatisfying(spec -> assertThat(spec.plan()).isEmpty());
  }

  @Test
  void readValue_whenCollectionsOmitted_returnsEmptyCollections() throws IOException {
    var document =
        objectMapper.readValue(
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: empty
            spec:
              sources: {}
            """,
            WorkstationProfileDocument.class);

    assertThat(document.metadata())
        .hasValueSatisfying(metadata -> assertThat(metadata.labels()).isEmpty());
    assertThat(document.spec()).hasValueSatisfying(spec -> assertThat(spec.vars()).isEmpty());
    assertThat(document.spec())
        .flatMap(spec -> spec.sources())
        .hasValueSatisfying(sources -> assertThat(sources.entries()).isEmpty());
  }
}
