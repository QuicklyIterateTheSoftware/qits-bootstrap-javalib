package eu.wohlben.qits.bootstrap.graph.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseDeclarationTest {

  @Test
  void aMavenLibraryDeclaresOneJar() {
    assertEquals(
        List.of(new DeclaredArtifact("maven", "eu.wohlben.qits:qits-containers-driver", null)),
        ReleaseDeclaration.artifacts(Estate.file("qits-containers-javalib", "release.yml")));
  }

  /** The image coordinate is the declared one, not the repository's name. */
  @Test
  void anOciRepositoryDeclaresItsImageBehindAWallOfComments() {
    assertEquals(
        List.of(new DeclaredArtifact("docker", "qits/qits-oci-postgresql", null)),
        ReleaseDeclaration.artifacts(Estate.file("qits-database-oci", "release.yml")));
  }

  /** contracts:, userflows: and a release: slot with a block script around the list do not matter. */
  @Test
  void aServiceDeclaresItsImageAndItsDocs() {
    assertEquals(
        List.of(
            new DeclaredArtifact("docker", "qits/qits-ci", null),
            new DeclaredArtifact("docs", "@apidocs/qits-ci", null)),
        ReleaseDeclaration.artifacts(Estate.file("qits-ci-service", "release.yml")));
  }

  @Test
  void aModulePathIsCarried() {
    assertEquals(
        List.of(
            new DeclaredArtifact("maven", "eu.wohlben.qits:qits-commands", "qits-commands"),
            new DeclaredArtifact("maven", "eu.wohlben.qits:qits-coding-agents", "qits-coding-agents")),
        ReleaseDeclaration.artifacts(
            """
            archetype: maven-library
            artifacts:
              - { type: maven, name: "eu.wohlben.qits:qits-commands", path: qits-commands, sbom: qits-commands/target/sbom.json }
              - { type: maven, name: "eu.wohlben.qits:qits-coding-agents", path: qits-coding-agents, link: [qits-commands] }
            """));
  }

  @Test
  void noArtifactsKeyOrAnEmptyFileIsNoArtifacts() {
    assertTrue(ReleaseDeclaration.artifacts("archetype: maven-library\n").isEmpty());
    assertTrue(ReleaseDeclaration.artifacts("").isEmpty());
    assertTrue(ReleaseDeclaration.artifacts("# only a comment\n").isEmpty());
    assertTrue(ReleaseDeclaration.artifacts(null).isEmpty());
    assertTrue(ReleaseDeclaration.artifacts("artifacts:\n").isEmpty());
  }

  @Test
  void artifactsThatAreNotAListAreRefusedClearly() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReleaseDeclaration.artifacts("artifacts: qits/qits-ci\n"));
    assertTrue(refused.getMessage().contains("artifacts must be a list"), refused.getMessage());
    assertTrue(refused.getMessage().contains(ReleaseDeclaration.PATH), refused.getMessage());

    assertThrows(
        IllegalArgumentException.class,
        () -> ReleaseDeclaration.artifacts("artifacts:\n  type: maven\n"));
  }

  @Test
  void anEntryWithoutATypeOrNameIsRefused() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReleaseDeclaration.artifacts("artifacts:\n  - { type: maven }\n"));
    assertTrue(refused.getMessage().contains("artifacts[0] has no name"), refused.getMessage());
  }

  @Test
  void aFileThatIsNotYamlIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> ReleaseDeclaration.artifacts("artifacts: [\n"));
  }
}
