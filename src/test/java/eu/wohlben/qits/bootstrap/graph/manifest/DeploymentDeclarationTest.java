package eu.wohlben.qits.bootstrap.graph.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentDeclarationTest {

  /** Colons and commas inside the values, long comment blocks between the keys. */
  @Test
  void theArtifactsServiceDeploysAsQitsArtifactsWithADatabaseAndAClient() {
    assertEquals(
        new Deployment("qits-artifacts", List.of("postgresql:db", "idp:client")),
        DeploymentDeclaration.parse(Estate.file("qits-artifacts-service", "deployments.yml")));
  }

  /** A three-segment resource keeps its colons. */
  @Test
  void theCiServiceDeclaresTwoDatabases() {
    assertEquals(
        new Deployment(
            "qits-ci",
            List.of("postgresql:db", "postgresql:eventstream:qits_ci_eventstream", "idp:client")),
        DeploymentDeclaration.parse(Estate.file("qits-ci-service", "deployments.yml")));
  }

  @Test
  void absentKeysAreNoApplicationAndNoResources() {
    assertEquals(
        new Deployment(null, List.of()), DeploymentDeclaration.parse("routes: /ci\n"));
    assertEquals(new Deployment(null, List.of()), DeploymentDeclaration.parse(""));
    assertEquals(new Deployment(null, List.of()), DeploymentDeclaration.parse(null));
  }

  @Test
  void resourcesAreSplitOnCommasAndTrimmed() {
    assertEquals(
        new Deployment("qits-x", List.of("postgresql:db", "idp:client")),
        DeploymentDeclaration.parse("application: qits-x\nresources:  postgresql:db ,idp:client ,\n"));
  }

  @Test
  void aListWhereAStringBelongsIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DeploymentDeclaration.parse("resources:\n  - postgresql:db\n"));
  }
}
