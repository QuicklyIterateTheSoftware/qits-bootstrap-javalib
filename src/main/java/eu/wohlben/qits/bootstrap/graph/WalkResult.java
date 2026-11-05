package eu.wohlben.qits.bootstrap.graph;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one {@link Walk} did.
 *
 * @param visited every offer, in order, with its answer
 * @param cycles the non-trivial strongly connected components over the walked kinds, in {@link
 *     ProjectGraph#components} order — never offered
 * @param unreached every repository never offered — a cycle member, downstream of one, downstream
 *     of a HELD, or left when the walk stopped — in {@link ProjectGraph#nodes()} order
 * @param stop the FAILED or RECOMPUTE answer that ended the walk, empty when it ran out of offers
 */
public record WalkResult(
    List<Visit> visited, List<Set<String>> cycles, List<String> unreached, Optional<Visit> stop) {

  public WalkResult {
    visited = List.copyOf(visited);
    cycles = List.copyOf(cycles);
    unreached = List.copyOf(unreached);
  }

  /** One offer and its answer. */
  public record Visit(String repository, Verdict verdict) {}
}
