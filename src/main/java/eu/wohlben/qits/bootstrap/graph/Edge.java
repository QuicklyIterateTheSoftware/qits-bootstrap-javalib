package eu.wohlben.qits.bootstrap.graph;

import java.util.Objects;

/**
 * One reason {@code upstream} goes before {@code downstream}.
 *
 * <p>Exactly one of {@code via} and {@code resource} is set, by kind: an ARTIFACT edge names the
 * coordinate the downstream pins, a RUNTIME edge the {@code resources:} entry it declares. Two
 * pins of the same coordinate are one edge; two coordinates between the same pair are two.
 *
 * @param upstream the repository that publishes or provides
 * @param downstream the repository that pins or declares
 * @param kind why
 * @param via the coordinate, for ARTIFACT; null for RUNTIME
 * @param resource the resource entry verbatim, e.g. {@code postgresql:db}, for RUNTIME; null for
 *     ARTIFACT
 */
public record Edge(
    String upstream, String downstream, EdgeKind kind, Coordinate via, String resource) {

  public Edge {
    Objects.requireNonNull(upstream, "upstream");
    Objects.requireNonNull(downstream, "downstream");
    Objects.requireNonNull(kind, "kind");
    if ((kind == EdgeKind.ARTIFACT) != (via != null)
        || (kind == EdgeKind.RUNTIME) != (resource != null)) {
      throw new IllegalArgumentException(
          "an ARTIFACT edge carries exactly a via, a RUNTIME edge exactly a resource");
    }
  }

  /** An ARTIFACT edge. */
  public static Edge artifact(String upstream, String downstream, Coordinate via) {
    return new Edge(upstream, downstream, EdgeKind.ARTIFACT, via, null);
  }

  /** A RUNTIME edge. */
  public static Edge runtime(String upstream, String downstream, String resource) {
    return new Edge(upstream, downstream, EdgeKind.RUNTIME, null, resource);
  }
}
