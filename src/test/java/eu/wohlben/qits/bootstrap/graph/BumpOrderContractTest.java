package eu.wohlben.qits.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * qits-maintenance's {@code BumpOrder.nextUpTo} READY picks, reproduced through {@link Walk} the
 * way its next caller will: the candidates are the nodes in listing order, the producers ledger
 * answers {@link ProjectSource#produces}, a change is a {@link Pin}; the visitor answers HELD for a
 * held candidate, records a free one as a pick and answers HELD for it too (a pick's consumers wait
 * on its release), and RECOMPUTE once the room is used up. Scenarios from {@code BumpOrderTest}.
 *
 * <p>The cycle BREAK ({@code nextUpTo}'s one extra pick, qits-1696) is not the walk's: a knot is
 * reported in {@link WalkResult#cycles()} and its members are never offered, so choosing which one
 * to break stays with the caller.
 */
class BumpOrderContractTest {

  private static final String LIB = "qits-eventstream-javalib";
  private static final String SERVICE = "qits-ci-service";
  private static final String WRAPPER = "qits-qits";

  /** {@code ArtifactGraph.producerKey} to the publishing repository, as BumpOrderTest has it. */
  private static final Map<String, String> PRODUCERS =
      Map.of(
          "maven eu.wohlben.qits:qits-eventstream", LIB,
          "docker qits/qits-ci", SERVICE);

  record Change(String ecosystem, String name) {}

  record Candidate(String repository, boolean held, List<Change> changes) {}

  private static Candidate candidate(String repository, Change... changes) {
    return new Candidate(repository, false, List.of(changes));
  }

  private static Candidate held(String repository, Change... changes) {
    return new Candidate(repository, true, List.of(changes));
  }

  private static Change on(String ecosystem, String name) {
    return new Change(ecosystem, name);
  }

  /** The candidates as a project: only they are nodes, so a producer that is not owed is no edge. */
  private static ProjectSource source(List<Candidate> candidates, Map<String, String> producers) {
    return new ProjectSource() {
      @Override
      public List<String> repositories() {
        return candidates.stream().map(Candidate::repository).toList();
      }

      @Override
      public List<Pin> consumes(String repository) {
        List<Pin> pins = new ArrayList<>();
        for (Candidate candidate : candidates) {
          if (candidate.repository().equals(repository)) {
            for (Change change : candidate.changes()) {
              Ecosystem.of(change.ecosystem())
                  .ifPresent(
                      ecosystem ->
                          pins.add(
                              new Pin(new Coordinate(ecosystem, change.name()), "2", "pom.xml", "property")));
            }
          }
        }
        return pins;
      }

      @Override
      public Set<Coordinate> produces(String repository) {
        Set<Coordinate> produced = new LinkedHashSet<>();
        producers.forEach(
            (key, producer) -> {
              int space = key.indexOf(' ');
              Optional<Ecosystem> ecosystem = Ecosystem.of(key.substring(0, space));
              if (producer.equals(repository) && ecosystem.isPresent()) {
                produced.add(new Coordinate(ecosystem.get(), key.substring(space + 1)));
              }
            });
        return produced;
      }

      @Override
      public Optional<Deployment> deployment(String repository) {
        return Optional.empty();
      }
    };
  }

  private static WalkResult picks(
      List<Candidate> candidates, Map<String, String> producers, int n, List<String> picks) {
    List<String> listing = candidates.stream().map(Candidate::repository).toList();
    Set<String> heldOnes = new LinkedHashSet<>();
    candidates.stream().filter(Candidate::held).forEach(c -> heldOnes.add(c.repository()));
    ProjectGraph graph = ProjectGraph.of(source(candidates, producers), Map.of());
    return Walk.run(
        graph,
        Set.of(EdgeKind.ARTIFACT),
        Comparator.comparingInt(listing::indexOf),
        repository -> {
          if (heldOnes.contains(repository)) {
            return Verdict.HELD;
          }
          picks.add(repository);
          return picks.size() == n ? Verdict.RECOMPUTE : Verdict.HELD;
        });
  }

  private static List<String> picks(List<Candidate> candidates, Map<String, String> producers, int n) {
    List<String> picks = new ArrayList<>();
    picks(candidates, producers, n, picks);
    return picks;
  }

  /** theDeepestUpstreamGoesFirstWhateverTheListingOrderIs / theConsumerOfAReadyRepositoryIsNotABreak */
  @Test
  void theDeepestUpstreamGoesFirstAndItsConsumerWaits() {
    List<Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("maven", "io.quarkus.platform:quarkus-bom")));

    assertEquals(List.of(LIB), picks(candidates, PRODUCERS, 1));
    assertEquals(List.of(LIB), picks(candidates, PRODUCERS, 8));
  }

  /** anUpstreamThatIsNotOwedABumpBlocksNothing */
  @Test
  void anUpstreamThatIsNotOwedBlocksNothing() {
    assertEquals(
        List.of(SERVICE),
        picks(List.of(candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream"))), PRODUCERS, 1));
  }

  /** aSubmoduleEdgeIsMatchedByName… and aPackageNamedLikeARepositoryIsNotASubmodule */
  @Test
  void aSubmoduleIsMatchedByNameAndOnlyAsAGitlink() {
    assertEquals(
        List.of(SERVICE),
        picks(
            List.of(
                candidate(WRAPPER, on("gitlink", SERVICE)),
                candidate(SERVICE, on("maven", "io.quarkus.platform:quarkus-bom"))),
            PRODUCERS,
            8));
    assertEquals(
        List.of(WRAPPER, SERVICE),
        picks(
            List.of(
                candidate(WRAPPER, on("npm", SERVICE)),
                candidate(SERVICE, on("maven", "io.quarkus.platform:quarkus-bom"))),
            PRODUCERS,
            8));
  }

  /** aHeldCandidateIsNeverThePick… / aCandidateWhoseUpstreamIsHeldStillWaits / everythingHeld… */
  @Test
  void heldIsNeverPickedAndStillBlocks() {
    assertEquals(
        List.of(WRAPPER),
        picks(
            List.of(
                held(LIB, on("maven", "io.quarkus.platform:quarkus-bom")),
                candidate(WRAPPER, on("npm", "@angular/core"))),
            PRODUCERS,
            1));
    assertEquals(
        List.of(),
        picks(
            List.of(
                candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
                held(LIB, on("maven", "io.quarkus.platform:quarkus-bom"))),
            PRODUCERS,
            1));
  }

  /** everyReadyCandidateGoesUpToTheFreeSlotsInListingOrder */
  @Test
  void everyReadyCandidateGoesUpToTheRoomInListingOrder() {
    List<Candidate> candidates = new ArrayList<>();
    for (String name : List.of("qits-c", "qits-a", "qits-b", "qits-d", "qits-e")) {
      candidates.add(candidate(name, on("maven", "io.quarkus.platform:quarkus-bom")));
    }

    List<String> picked = new ArrayList<>();
    WalkResult result = picks(candidates, PRODUCERS, 3, picked);
    assertEquals(List.of("qits-c", "qits-a", "qits-b"), picked);
    assertEquals(Optional.of(new WalkResult.Visit("qits-b", Verdict.RECOMPUTE)), result.stop());

    assertEquals(5, picks(candidates, PRODUCERS, 7).size(), "never more than are READY");
  }

  /** freeSlotsDoNotLetAConsumerGoBesideItsOwedUpstream */
  @Test
  void roomDoesNotLetAConsumerGoBesideItsOwedUpstream() {
    assertEquals(
        List.of(LIB),
        picks(
            List.of(
                candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
                candidate(LIB, on("maven", "io.quarkus.platform:quarkus-bom")),
                held(WRAPPER, on("npm", "left-pad"))),
            PRODUCERS,
            5));
  }

  /**
   * aCycleStillDispatchesSomething: nextUpTo breaks this knot; the walk picks nothing and reports
   * the knot for the caller to break.
   */
  @Test
  void aKnotIsReportedForTheCallerToBreak() {
    List<String> picked = new ArrayList<>();
    WalkResult result =
        picks(
            List.of(
                candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
                candidate(LIB, on("docker", "qits/qits-ci"))),
            PRODUCERS,
            8,
            picked);

    assertTrue(picked.isEmpty());
    assertEquals(List.of(Set.of(SERVICE, LIB)), result.cycles());
  }

  /**
   * aCycleThroughAHeldCandidateIsNotBroken — THE ONE PLACE A CALLER MUST ADD A RULE. BumpOrder's
   * knot is judged among FREE candidates only, so a ring through a held one is no knot and is never
   * broken. The walk's cycles() are over the whole graph and do report it: a caller that breaks
   * cycles must first drop every cycle with a held member (and a member waiting on a held one).
   */
  @Test
  void aRingThroughAHeldCandidateIsStillACycleToTheWalk() {
    List<String> picked = new ArrayList<>();
    WalkResult result =
        picks(
            List.of(
                candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
                held(LIB, on("docker", "qits/qits-ci"))),
            PRODUCERS,
            8,
            picked);

    assertTrue(picked.isEmpty(), "no READY pick either way");
    assertEquals(List.of(Set.of(SERVICE, LIB)), result.cycles());
  }
}
