package eu.wohlben.qits.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectGraphTest {

  private static final Set<EdgeKind> ALL = Set.of(EdgeKind.ARTIFACT, EdgeKind.RUNTIME);
  private static final Set<EdgeKind> ARTIFACT = Set.of(EdgeKind.ARTIFACT);

  private static final Map<String, String> PROVIDERS =
      Map.of("postgresql", "qits-oci-postgresql", "idp", "qits-idp");

  @Test
  void theCoordinateKeyIsArtifactGraphsProducerKey() {
    // ArtifactGraph.producerKey(ecosystem, name) is `ecosystem + " " + name` with the wire name.
    assertEquals(
        "maven eu.wohlben.qits:qits-eventstream",
        new Coordinate(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream").key());
    assertEquals("gitlink qits-ci-service", new Coordinate(Ecosystem.GITLINK, "qits-ci-service").key());
  }

  @Test
  void everyEcosystemMakesAnArtifactEdgeFromItsProducer() {
    Source source =
        new Source()
            .produces("lib", Ecosystem.MAVEN, "eu.wohlben.qits:lib")
            .produces("ui", Ecosystem.NPM, "@qits/ui")
            .produces("base", Ecosystem.DOCKER, "qits/base")
            .repo("sub")
            .consumes("app", Ecosystem.MAVEN, "eu.wohlben.qits:lib")
            .consumes("app", Ecosystem.NPM, "@qits/ui")
            .consumes("app", Ecosystem.DOCKER, "qits/base")
            .consumes("app", Ecosystem.GITLINK, "sub");

    ProjectGraph graph = source.graph();

    assertEquals(List.of("lib", "ui", "base", "sub", "app"), graph.nodes());
    assertEquals(
        List.of(
            Edge.artifact("lib", "app", new Coordinate(Ecosystem.MAVEN, "eu.wohlben.qits:lib")),
            Edge.artifact("ui", "app", new Coordinate(Ecosystem.NPM, "@qits/ui")),
            Edge.artifact("base", "app", new Coordinate(Ecosystem.DOCKER, "qits/base")),
            Edge.artifact("sub", "app", new Coordinate(Ecosystem.GITLINK, "sub"))),
        graph.edges());
    assertEquals(Set.of("lib", "ui", "base", "sub"), graph.upstreams("app", ALL));
    assertEquals(Set.of("app"), graph.downstreams("sub", ALL));
  }

  /** The implied gitlink coordinate is GITLINK only: a package named like a repository is nothing. */
  @Test
  void aPackageNamedLikeARepositoryIsNoEdge() {
    Source source = new Source().repo("sub").consumes("app", Ecosystem.NPM, "sub");

    assertTrue(source.graph().edges().isEmpty());
  }

  @Test
  void runtimeEdgesComeFromResourcesThroughTheProviderMap() {
    Source source =
        new Source()
            .deploys("qits-database-oci", "qits-oci-postgresql")
            .deploys("qits-idp-service", "qits-idp", "postgresql:db:qits_platform_idp", "idp:client")
            .deploys("qits-ci-service", "qits-ci", "postgresql:db", "idp:client", "smtp:mail");

    ProjectGraph graph = ProjectGraph.of(source, PROVIDERS);

    assertEquals(
        List.of(
            Edge.runtime("qits-database-oci", "qits-idp-service", "postgresql:db:qits_platform_idp"),
            Edge.runtime("qits-database-oci", "qits-ci-service", "postgresql:db"),
            Edge.runtime("qits-idp-service", "qits-ci-service", "idp:client")),
        graph.edges(),
        "the idp's own idp:client is no self edge");
    assertEquals(
        List.of(
            new ProjectGraph.Unresolved(
                "qits-ci-service", "smtp:mail", "no provider for resource kind 'smtp'")),
        graph.unresolved(),
        "an unknown kind is listed, not an edge and not an error");
    assertTrue(graph.upstreams("qits-ci-service", ARTIFACT).isEmpty());
  }

  @Test
  void aProviderNoNodeDeploysIsUnresolved() {
    Source source = new Source().deploys("qits-ci-service", "qits-ci", "postgresql:db");

    ProjectGraph graph = ProjectGraph.of(source, PROVIDERS);

    assertTrue(graph.edges().isEmpty());
    assertEquals(1, graph.unresolved().size());
    assertTrue(graph.unresolved().get(0).reason().contains("qits-oci-postgresql"));
  }

  @Test
  void aRepositoryConsumingWhatItProducesHasNoSelfEdge() {
    Source source =
        new Source()
            .produces("lib", Ecosystem.MAVEN, "eu.wohlben.qits:lib")
            .consumes("lib", Ecosystem.MAVEN, "eu.wohlben.qits:lib")
            .consumes("lib", Ecosystem.GITLINK, "lib");

    assertTrue(source.graph().edges().isEmpty());
  }

  /** Produced outside the project, or by nobody: third-party, no edge. */
  @Test
  void aCoordinateNoNodeProducesIsNoEdge() {
    Source source =
        new Source()
            .consumes("app", Ecosystem.MAVEN, "io.quarkus.platform:quarkus-bom")
            .consumes("app", Ecosystem.GITLINK, "not-a-node");

    ProjectGraph graph = source.graph();

    assertTrue(graph.edges().isEmpty());
    assertTrue(graph.unresolved().isEmpty());
  }

  @Test
  void twoProducersOfOneCoordinateTakeTheFirstAndSaySo() {
    Source source =
        new Source()
            .produces("a", Ecosystem.MAVEN, "g:x")
            .produces("b", Ecosystem.MAVEN, "g:x")
            .consumes("c", Ecosystem.MAVEN, "g:x");

    ProjectGraph graph = source.graph();

    assertEquals(List.of(Edge.artifact("a", "c", new Coordinate(Ecosystem.MAVEN, "g:x"))), graph.edges());
    assertEquals(List.of(new ProjectGraph.Ambiguity("maven g:x", List.of("a", "b"))), graph.ambiguous());
  }

  /** a -> b -> c -> a, plus c -> d -> e: the cycle first, then the tail in order. */
  @Test
  void componentsAreACycleAndItsTailInDependencyOrder() {
    Source source =
        new Source()
            .repo("e")
            .repo("d")
            .repo("a")
            .repo("b")
            .repo("c")
            .edge("a", "b")
            .edge("b", "c")
            .edge("c", "a")
            .edge("c", "d")
            .edge("d", "e");

    List<Set<String>> components = source.graph().components(ALL);

    assertEquals(List.of(Set.of("a", "b", "c"), Set.of("d"), Set.of("e")), components);
    assertEquals(List.of("a", "b", "c"), List.copyOf(components.get(0)), "node order within");
    assertThrows(UnsupportedOperationException.class, () -> components.get(0).add("x"));
  }

  @Test
  void unrelatedComponentsFollowNodeOrder() {
    Source source = new Source().repo("z").repo("y").repo("x").edge("x", "z");

    assertEquals(
        List.of(Set.of("y"), Set.of("x"), Set.of("z")), source.graph().components(ALL),
        "y is ready first by position; z waits for x");
  }

  @Test
  void anUnknownRepositoryIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new Source().repo("a").graph().upstreams("b", ALL));
  }

  /** The SCC search keeps its own stack: a long chain does not overflow the call stack. */
  @Test
  void aLongChainIsOrderedWithoutRecursion() {
    Source source = new Source();
    for (int i = 0; i < 20_000; i++) {
      source.repo("r" + i);
    }
    for (int i = 0; i + 1 < 20_000; i++) {
      source.consumes("r" + (i + 1), Ecosystem.GITLINK, "r" + i);
    }

    List<Set<String>> components = source.graph().components(ALL);

    assertEquals(20_000, components.size());
    assertEquals(Set.of("r0"), components.get(0));
    assertEquals(Set.of("r19999"), components.get(19_999));
  }
}
