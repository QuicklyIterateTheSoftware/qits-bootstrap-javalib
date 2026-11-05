package eu.wohlben.qits.bootstrap.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Offers a graph's repositories to a {@link Visitor}, upstream first, one at a time.
 *
 * <ul>
 *   <li>A repository is <b>offerable</b> when every upstream over the walked kinds has answered
 *       {@link Verdict#DONE}. Of the offerable ones the lowest under {@code tieBreak} goes next, and
 *       the set is re-evaluated after every answer — a DONE may make a lower-sorting repository
 *       offerable ahead of one that was already waiting.
 *   <li>{@link Verdict#HELD} leaves the repository unfinished: it is not offered again and its
 *       downstreams are never offered in this walk, while everything else goes on.
 *   <li>{@link Verdict#FAILED} and {@link Verdict#RECOMPUTE} stop the walk at once.
 *   <li>A member of a cycle over the walked kinds is never offered, nor is anything downstream of
 *       one. The cycles are reported, so a caller that wants to break one decides how itself.
 * </ul>
 */
public final class Walk {

  private Walk() {}

  /**
   * @param kinds which edges order the walk; others are ignored
   * @param tieBreak the order among offerable repositories; ties it leaves are broken by {@link
   *     ProjectGraph#nodes()} order
   * @throws NullPointerException when the visitor answers null
   */
  public static WalkResult run(
      ProjectGraph graph, Set<EdgeKind> kinds, Comparator<String> tieBreak, Visitor visitor) {
    Comparator<String> order = tieBreak.thenComparingInt(graph::position);

    List<Set<String>> cycles = new ArrayList<>();
    Set<String> blocked = new HashSet<>();
    for (Set<String> component : graph.components(kinds)) {
      if (component.size() > 1) {
        cycles.add(component);
        blocked.addAll(component);
      }
    }
    // Everything downstream of a cycle waits on it, so it is never offered either.
    Map<String, List<String>> out = graph.adjacency(kinds);
    Deque<String> frontier = new ArrayDeque<>(blocked);
    while (!frontier.isEmpty()) {
      for (String downstream : out.get(frontier.pop())) {
        if (blocked.add(downstream)) {
          frontier.push(downstream);
        }
      }
    }

    Map<String, Set<String>> upstreams = new java.util.HashMap<>();
    for (String node : graph.nodes()) {
      upstreams.put(node, graph.upstreams(node, kinds));
    }

    Set<String> done = new HashSet<>();
    Set<String> answered = new HashSet<>();
    List<WalkResult.Visit> visited = new ArrayList<>();
    Optional<WalkResult.Visit> stop = Optional.empty();
    while (stop.isEmpty()) {
      String next = null;
      for (String node : graph.nodes()) {
        if (answered.contains(node) || blocked.contains(node)) {
          continue;
        }
        if (done.containsAll(upstreams.get(node))
            && (next == null || order.compare(node, next) < 0)) {
          next = node;
        }
      }
      if (next == null) {
        break;
      }
      Verdict verdict = visitor.visit(next);
      if (verdict == null) {
        throw new NullPointerException("the visitor answered null for " + next);
      }
      answered.add(next);
      WalkResult.Visit visit = new WalkResult.Visit(next, verdict);
      visited.add(visit);
      switch (verdict) {
        case DONE -> done.add(next);
        case HELD -> {}
        case FAILED, RECOMPUTE -> stop = Optional.of(visit);
      }
    }

    List<String> unreached = new ArrayList<>();
    for (String node : graph.nodes()) {
      if (!answered.contains(node)) {
        unreached.add(node);
      }
    }
    return new WalkResult(visited, cycles, unreached, stop);
  }
}
