package eu.wohlben.qits.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.WalkResult.Visit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WalkTest {

  private static final Set<EdgeKind> ALL = Set.of(EdgeKind.ARTIFACT, EdgeKind.RUNTIME);
  private static final Comparator<String> ALPHABET = Comparator.naturalOrder();

  private static List<String> order(WalkResult result) {
    return result.visited().stream().map(Visit::repository).toList();
  }

  private static WalkResult walk(ProjectGraph graph, Comparator<String> tieBreak, Map<String, Verdict> answers) {
    return Walk.run(graph, ALL, tieBreak, repository -> answers.getOrDefault(repository, Verdict.DONE));
  }

  @Test
  void upstreamsGoFirst() {
    Source source = new Source().repo("app").repo("svc").repo("lib").edge("lib", "svc").edge("svc", "app");

    WalkResult result = walk(source.graph(), ALPHABET, Map.of());

    assertEquals(List.of("lib", "svc", "app"), order(result));
    assertTrue(result.unreached().isEmpty());
    assertEquals(Optional.empty(), result.stop());
    assertTrue(result.cycles().isEmpty());
  }

  @Test
  void theTieBreakOrdersReadyRepositories() {
    Source source = new Source().repo("b").repo("c").repo("a");

    assertEquals(List.of("a", "b", "c"), order(walk(source.graph(), ALPHABET, Map.of())));
    assertEquals(
        List.of("c", "b", "a"), order(walk(source.graph(), ALPHABET.reversed(), Map.of())));
  }

  /** a releases b, which sorts before the already-waiting c. */
  @Test
  void readinessIsReEvaluatedAfterEveryAnswer() {
    Source source = new Source().repo("a").repo("b").repo("c").edge("a", "b");

    assertEquals(List.of("a", "b", "c"), order(walk(source.graph(), ALPHABET, Map.of())));
  }

  @Test
  void heldBlocksOnlyItsOwnDownstreams() {
    Source source =
        new Source()
            .repo("a")
            .repo("a-consumer")
            .repo("b")
            .repo("b-consumer")
            .edge("a", "a-consumer")
            .edge("b", "b-consumer");

    WalkResult result = walk(source.graph(), ALPHABET, Map.of("a", Verdict.HELD));

    assertEquals(
        List.of(new Visit("a", Verdict.HELD), new Visit("b", Verdict.DONE), new Visit("b-consumer", Verdict.DONE)),
        result.visited());
    assertEquals(List.of("a-consumer"), result.unreached());
    assertEquals(Optional.empty(), result.stop());
  }

  @Test
  void failedAndRecomputeStopAndNameTheRepository() {
    Source source = new Source().repo("a").repo("b").repo("c");
    for (Verdict stop : List.of(Verdict.FAILED, Verdict.RECOMPUTE)) {
      WalkResult result = walk(source.graph(), ALPHABET, Map.of("b", stop));

      assertEquals(Optional.of(new Visit("b", stop)), result.stop());
      assertEquals(List.of("a", "b"), order(result));
      assertEquals(List.of("c"), result.unreached());
    }
  }

  @Test
  void cyclesAreReportedAndNeverOfferedNorAnythingBelowThem() {
    Source source =
        new Source()
            .repo("p")
            .repo("q")
            .repo("x")
            .repo("y")
            .repo("z")
            .repo("tail")
            .repo("free")
            .edge("p", "q")
            .edge("q", "p")
            .edge("x", "y")
            .edge("y", "z")
            .edge("z", "x")
            .edge("z", "tail");

    WalkResult result = walk(source.graph(), ALPHABET, Map.of());

    assertEquals(List.of("free"), order(result));
    assertEquals(List.of(Set.of("p", "q"), Set.of("x", "y", "z")), result.cycles());
    assertEquals(List.of("p", "q", "x", "y", "z", "tail"), result.unreached());
  }

  /** A RUNTIME edge orders nothing when only ARTIFACT is walked. */
  @Test
  void onlyTheWalkedKindsOrder() {
    Source source =
        new Source()
            .deploys("app", "qits-app", "postgresql:db")
            .deploys("db", "qits-oci-postgresql");
    ProjectGraph graph = ProjectGraph.of(source, Map.of("postgresql", "qits-oci-postgresql"));
    List<String> seen = new ArrayList<>();

    Walk.run(graph, Set.of(EdgeKind.ARTIFACT), ALPHABET, repository -> {
      seen.add(repository);
      return Verdict.DONE;
    });
    assertEquals(List.of("app", "db"), seen);

    seen.clear();
    Walk.run(graph, ALL, ALPHABET, repository -> {
      seen.add(repository);
      return Verdict.DONE;
    });
    assertEquals(List.of("db", "app"), seen);
  }

  @Test
  void aNullVerdictIsRefusedClearly() {
    ProjectGraph graph = new Source().repo("a").graph();

    NullPointerException refused =
        assertThrows(NullPointerException.class, () -> Walk.run(graph, ALL, ALPHABET, repository -> null));
    assertTrue(refused.getMessage().contains("a"), refused.getMessage());
  }
}
