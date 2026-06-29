package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkstationProfileInterpolatorTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

  @Test
  void interpolate_whenVarsAndHostFactsPresent_resolvesManifestStringFields()
      throws IOException {
    JsonNode root = readTree(interpolationManifest());
    var interpolator =
        new WorkstationProfileInterpolator(
            Map.of("HOME", "/home/runtime", "USER", "runtime-user"),
            Map.of("host.os.arch", "amd64", "host.home", "/home/host"));

    JsonNode result = interpolator.interpolate(root);

    assertThat(text(result, "/spec/vars/binDir")).isEqualTo("/home/runtime/.local/bin");
    assertThat(text(result, "/spec/policy/statePath"))
        .isEqualTo("/home/runtime/.local/state/fluxion.json");
    assertThat(text(result, "/spec/sources/apt/0/spec/source"))
        .isEqualTo("deb https://example.test/runtime-user/amd64 stable");
    assertThat(text(result, "/spec/sources/apt/0/spec/sourceList"))
        .isEqualTo("/home/runtime/.config/apt/docker.list");
    assertThat(text(result, "/spec/sources/pacman/0/spec/config"))
        .isEqualTo("/home/runtime/.config/pacman.conf");
    assertThat(text(result, "/spec/plan/0/spec/commands/0"))
        .isEqualTo("install /home/runtime/.local/bin/tool /home/runtime/bin/tool");
    assertThat(text(result, "/spec/plan/0/spec/args/1")).isEqualTo("--arch=amd64");
    assertThat(text(result, "/spec/plan/0/spec/destination"))
        .isEqualTo("/home/runtime/.config/dest");
    assertThat(text(result, "/spec/plan/0/spec/configPath"))
        .isEqualTo("/home/runtime/.config/tool.yml");
    assertThat(text(result, "/spec/plan/1/spec/installPath"))
        .isEqualTo("/home/runtime/.local/bin/rg");
  }

  @Test
  void interpolate_whenPlanVariableUnresolved_reportsFieldPathAndPlanName()
      throws IOException {
    JsonNode root =
        readTree(
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: vars-test
            spec:
              plan:
                - name: setup
                  kind: commands
                  spec:
                    commands:
                      - "echo ${missing}"
            """);
    var interpolator = new WorkstationProfileInterpolator(Map.of(), Map.of());

    assertThatThrownBy(() -> interpolator.interpolate(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spec.plan[0].spec.commands[0]")
        .hasMessageContaining("plan entry 'setup'")
        .hasMessageContaining("${missing}");
  }

  @Test
  void interpolate_whenShellSyntaxPresent_leavesNonBracedSyntaxLiteral()
      throws IOException {
    JsonNode root =
        readTree(
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: shell-syntax-test
            spec:
              vars:
                binDir: ${HOME}/bin
              plan:
                - name: setup
                  kind: commands
                  spec:
                    commands:
                      - "echo $(whoami) `date` *.txt ${binDir}"
            """);
    var interpolator =
        new WorkstationProfileInterpolator(Map.of("HOME", "/home/runtime"), Map.of());

    JsonNode result = interpolator.interpolate(root);

    assertThat(text(result, "/spec/plan/0/spec/commands/0"))
        .isEqualTo("echo $(whoami) `date` *.txt /home/runtime/bin");
  }

  @Test
  void interpolate_whenNestedSpecVarUnresolved_reportsVarPath() throws IOException {
    JsonNode root =
        readTree(
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: vars-test
            spec:
              vars:
                binDir: ${missing}/bin
              plan: []
            """);
    var interpolator = new WorkstationProfileInterpolator(Map.of(), Map.of());

    assertThatThrownBy(() -> interpolator.interpolate(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spec.vars.binDir")
        .hasMessageContaining("${missing}");
  }

  private JsonNode readTree(String yaml) throws IOException {
    return objectMapper.readTree(yaml);
  }

  private String text(JsonNode node, String pointer) {
    return node.at(pointer).asText();
  }

  private String interpolationManifest() {
    return """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: interpolation-test
        spec:
          vars:
            configDir: ${HOME}/.config
            binDir: ${HOME}/.local/bin
            repoBase: https://example.test/${USER}/${host.os.arch}
          policy:
            statePath: ${HOME}/.local/state/fluxion.json
          sources:
            apt:
              - name: docker
                kind: apt-repository
                spec:
                  source: deb ${repoBase} stable
                  sourceList: ${configDir}/apt/docker.list
                  signingKeyUrl: ${repoBase}/gpg
                  keyring: ${configDir}/apt/docker.gpg
            pacman:
              - name: chaotic
                kind: pacman-repository
                spec:
                  server: ${repoBase}/$repo/$arch
                  config: ${configDir}/pacman.conf
          plan:
            - name: setup
              kind: commands
              spec:
                commands:
                  - install ${binDir}/tool ${HOME}/bin/tool
                args:
                  - --user=${USER}
                  - --arch=${host.os.arch}
                destination: ${configDir}/dest
                config: ${configDir}/tool.conf
                configPath: ${configDir}/tool.yml
            - name: binary
              kind: binary-downloads
              spec:
                url: ${repoBase}/rg.tar.gz
                installPath: ${binDir}/rg
        """;
  }
}
