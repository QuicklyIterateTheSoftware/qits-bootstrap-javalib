package eu.wohlben.qits.bootstrap.graph;

import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Which repository of a project goes before which, and why.
 *
 * <p><b>Nodes</b> are {@link ProjectSource#repositories()}, in that order; that order breaks every
 * tie below. <b>Edges</b> run upstream to downstream and only between nodes:
 *
 * <ul>
 *   <li><b>ARTIFACT</b> — the downstream pins a coordinate the upstream produces. Every node
 *       implicitly produces {@code Coordinate(GITLINK, <its own name>)}, so a submodule pin is an
 *       edge from the submodule's repository with no artifact involved — and only for GITLINK, so
 *       a package merely named like a repository invents nothing. An explicitly produced
 *       coordinate wins over the implied one, as qits-maintenance's {@code BumpOrder.producerOf}
 *       asks the ledger before the name. A pin nobody in the project produces is third-party and
 *       makes no edge.
 *   <li><b>RUNTIME</b> — the downstream's {@code resources:} entry {@code <kind>[:...]} names a
 *       kind whose provider application ({@code resourceProviders}) some node deploys as. A kind
 *       with no provider, or a provider no node deploys, is {@link #unresolved()}, not an error.
 * </ul>
 *
 * <p>No edge is a self edge. When two nodes produce one coordinate, or deploy as one application,
 * the first in {@code repositories()} order is the upstream and the tie is recorded in {@link
 * #ambiguous()}.
 */
public final class ProjectGraph {

  /** A resource entry no node provides. */
  public record Unresolved(String repository, String resource, String reason) {}

  /**
   * More than one node claims {@code subject} — a coordinate's {@link Coordinate#key()}, or {@code
   * application:<name>} — and {@code repositories.get(0)} was taken.
   */
  public record Ambiguity(String subject, List<String> repositories) {}

  private final List<String> nodes;
  private final Map<String, Integer> position;
  private final List<Edge> edges;
  private final List<Unresolved> unresolved;
  private final List<Ambiguity> ambiguous;

  private ProjectGraph(
      List<String> nodes, List<Edge> edges, List<Unresolved> unresolved, List<Ambiguity> ambiguous) {
    this.nodes = List.copyOf(nodes);
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < this.nodes.size(); i++) {
      index.put(this.nodes.get(i), i);
    }
    this.position = Collections.unmodifiableMap(index);
    this.edges = List.copyOf(edges);
    this.unresolved = List.copyOf(unresolved);
    this.ambiguous = List.copyOf(ambiguous);
  }

  /**
   * The graph of {@code source}'s facts.
   *
   * @param resourceProviders resource kind ({@code postgresql}, {@code idp}) to the application
   *     that provides it ({@code qits-oci-postgresql}, {@code qits-idp})
   * @throws IllegalArgumentException when {@code repositories()} names one repository twice
   */
  public static ProjectGraph of(ProjectSource source, Map<String, String> resourceProviders) {
    List<String> nodes = List.copyOf(source.repositories());
    if (new LinkedHashSet<>(nodes).size() != nodes.size()) {
      throw new IllegalArgumentException("repositories() names a repository twice: " + nodes);
    }
    List<Ambiguity> ambiguous = new ArrayList<>();

    // Who produces what: explicit claims first, in node order, then each node's implied gitlink.
    Map<Coordinate, List<String>> claims = new LinkedHashMap<>();
    for (String node : nodes) {
      for (Coordinate coordinate : source.produces(node)) {
        List<String> claimants = claims.computeIfAbsent(coordinate, key -> new ArrayList<>());
        if (!claimants.contains(node)) {
          claimants.add(node);
        }
      }
    }
    Map<Coordinate, String> producer = new HashMap<>();
    for (Map.Entry<Coordinate, List<String>> claim : claims.entrySet()) {
      producer.put(claim.getKey(), claim.getValue().get(0));
      if (claim.getValue().size() > 1) {
        ambiguous.add(new Ambiguity(claim.getKey().key(), List.copyOf(claim.getValue())));
      }
    }
    for (String node : nodes) {
      producer.putIfAbsent(new Coordinate(Ecosystem.GITLINK, node), node);
    }

    // Who deploys as what.
    Map<String, Deployment> deployments = new LinkedHashMap<>();
    Map<String, List<String>> deployers = new LinkedHashMap<>();
    for (String node : nodes) {
      Optional<Deployment> deployment = source.deployment(node);
      if (deployment != null && deployment.isPresent()) {
        deployments.put(node, deployment.get());
        String application = deployment.get().application();
        if (application != null) {
          deployers.computeIfAbsent(application, key -> new ArrayList<>()).add(node);
        }
      }
    }
    for (Map.Entry<String, List<String>> deployer : deployers.entrySet()) {
      if (deployer.getValue().size() > 1) {
        ambiguous.add(
            new Ambiguity("application:" + deployer.getKey(), List.copyOf(deployer.getValue())));
      }
    }

    Set<Edge> edges = new LinkedHashSet<>();
    List<Unresolved> unresolved = new ArrayList<>();
    for (String downstream : nodes) {
      for (Pin pin : source.consumes(downstream)) {
        String upstream = producer.get(pin.coordinate());
        if (upstream != null && !upstream.equals(downstream)) {
          edges.add(Edge.artifact(upstream, downstream, pin.coordinate()));
        }
      }
      Deployment deployment = deployments.get(downstream);
      if (deployment == null) {
        continue;
      }
      for (String resource : deployment.resources()) {
        int colon = resource.indexOf(':');
        String kind = colon < 0 ? resource : resource.substring(0, colon);
        String application = resourceProviders.get(kind);
        if (application == null) {
          unresolved.add(
              new Unresolved(downstream, resource, "no provider for resource kind '" + kind + "'"));
          continue;
        }
        List<String> providers = deployers.get(application);
        if (providers == null) {
          unresolved.add(
              new Unresolved(
                  downstream,
                  resource,
                  "no repository deploys '" + application + "', the provider of '" + kind + "'"));
          continue;
        }
        String upstream = providers.get(0);
        if (!upstream.equals(downstream)) {
          edges.add(Edge.runtime(upstream, downstream, resource));
        }
      }
    }
    return new ProjectGraph(nodes, new ArrayList<>(edges), unresolved, ambiguous);
  }

  /** Every repository, in {@link ProjectSource#repositories()} order. */
  public List<String> nodes() {
    return nodes;
  }

  /** Every edge, grouped by downstream in node order, then in discovery order. */
  public List<Edge> edges() {
    return edges;
  }

  /** Resource entries no node provides, in node order. */
  public List<Unresolved> unresolved() {
    return unresolved;
  }

  /** Coordinates and applications claimed by more than one node. */
  public List<Ambiguity> ambiguous() {
    return ambiguous;
  }

  /** The direct upstreams of {@code repository} over {@code kinds}, in node order. */
  public Set<String> upstreams(String repository, Set<EdgeKind> kinds) {
    require(repository);
    Set<String> found = new java.util.TreeSet<>(this::compare);
    for (Edge edge : edges) {
      if (edge.downstream().equals(repository) && kinds.contains(edge.kind())) {
        found.add(edge.upstream());
      }
    }
    return Collections.unmodifiableSet(new LinkedHashSet<>(found));
  }

  /** The direct downstreams of {@code repository} over {@code kinds}, in node order. */
  public Set<String> downstreams(String repository, Set<EdgeKind> kinds) {
    require(repository);
    Set<String> found = new java.util.TreeSet<>(this::compare);
    for (Edge edge : edges) {
      if (edge.upstream().equals(repository) && kinds.contains(edge.kind())) {
        found.add(edge.downstream());
      }
    }
    return Collections.unmodifiableSet(new LinkedHashSet<>(found));
  }

  /**
   * The strongly connected components over {@code kinds}, upstream components first; among
   * components with no order between them, the one whose first node comes first in {@link #nodes()}
   * goes first. Each component iterates in node order. A component of size one is an ordinary node;
   * a larger one is a cycle.
   */
  public List<Set<String>> components(Set<EdgeKind> kinds) {
    Map<String, List<String>> out = adjacency(kinds);
    List<List<String>> found = tarjan(out);

    // Order the condensation: Kahn's algorithm, ready components by their first node's position.
    Map<String, Integer> componentOf = new HashMap<>();
    List<Set<String>> components = new ArrayList<>();
    for (List<String> members : found) {
      List<String> sorted = new ArrayList<>(members);
      sorted.sort(this::compare);
      int id = components.size();
      for (String member : sorted) {
        componentOf.put(member, id);
      }
      components.add(Collections.unmodifiableSet(new LinkedHashSet<>(sorted)));
    }
    int[] indegree = new int[components.size()];
    List<Set<Integer>> next = new ArrayList<>();
    for (int i = 0; i < components.size(); i++) {
      next.add(new LinkedHashSet<>());
    }
    for (Map.Entry<String, List<String>> from : out.entrySet()) {
      int a = componentOf.get(from.getKey());
      for (String to : from.getValue()) {
        int b = componentOf.get(to);
        if (a != b && next.get(a).add(b)) {
          indegree[b]++;
        }
      }
    }
    PriorityQueue<Integer> ready =
        new PriorityQueue<>(
            (x, y) ->
                Integer.compare(
                    position.get(components.get(x).iterator().next()),
                    position.get(components.get(y).iterator().next())));
    for (int i = 0; i < components.size(); i++) {
      if (indegree[i] == 0) {
        ready.add(i);
      }
    }
    List<Set<String>> ordered = new ArrayList<>();
    while (!ready.isEmpty()) {
      int at = ready.poll();
      ordered.add(components.get(at));
      for (int b : next.get(at)) {
        if (--indegree[b] == 0) {
          ready.add(b);
        }
      }
    }
    return List.copyOf(ordered);
  }

  /** Position in {@link #nodes()}; package-private for the walk. */
  int position(String repository) {
    return position.get(repository);
  }

  /** Node to its downstreams over {@code kinds}, in node order, every node present. */
  Map<String, List<String>> adjacency(Set<EdgeKind> kinds) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String node : nodes) {
      out.put(node, new ArrayList<>(downstreams(node, kinds)));
    }
    return out;
  }

  private int compare(String a, String b) {
    return Integer.compare(position.get(a), position.get(b));
  }

  private void require(String repository) {
    if (!position.containsKey(repository)) {
      throw new IllegalArgumentException(repository + " is not a node of this graph");
    }
  }

  /** Tarjan's algorithm with an explicit stack, so depth is bounded by memory, not the call stack. */
  private static List<List<String>> tarjan(Map<String, List<String>> out) {
    Map<String, Integer> index = new HashMap<>();
    Map<String, Integer> low = new HashMap<>();
    Deque<String> stack = new ArrayDeque<>();
    Set<String> onStack = new java.util.HashSet<>();
    List<List<String>> components = new ArrayList<>();
    int counter = 0;
    for (String root : out.keySet()) {
      if (index.containsKey(root)) {
        continue;
      }
      // Each frame: the node and how far through its successors it has got.
      Deque<int[]> cursors = new ArrayDeque<>();
      Deque<String> frames = new ArrayDeque<>();
      index.put(root, counter);
      low.put(root, counter);
      counter++;
      stack.push(root);
      onStack.add(root);
      frames.push(root);
      cursors.push(new int[] {0});
      while (!frames.isEmpty()) {
        String node = frames.peek();
        int[] cursor = cursors.peek();
        List<String> successors = out.get(node);
        if (cursor[0] < successors.size()) {
          String next = successors.get(cursor[0]++);
          if (!index.containsKey(next)) {
            index.put(next, counter);
            low.put(next, counter);
            counter++;
            stack.push(next);
            onStack.add(next);
            frames.push(next);
            cursors.push(new int[] {0});
          } else if (onStack.contains(next)) {
            low.put(node, Math.min(low.get(node), index.get(next)));
          }
          continue;
        }
        frames.pop();
        cursors.pop();
        if (!frames.isEmpty()) {
          String parent = frames.peek();
          low.put(parent, Math.min(low.get(parent), low.get(node)));
        }
        if (low.get(node).equals(index.get(node))) {
          List<String> component = new ArrayList<>();
          String member;
          do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
          } while (!member.equals(node));
          components.add(component);
        }
      }
    }
    return components;
  }
}
