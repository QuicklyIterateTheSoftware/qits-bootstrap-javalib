package eu.wohlben.qits.bootstrap.graph;

import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The facts a {@link ProjectGraph} is built from, sourced by the caller.
 *
 * <p>The library never sources a fact: qits-bootstrap-cli answers this from local checkouts,
 * qits-maintenance-service from its inventory and artifact ledger. Every method is asked only about
 * names {@link #repositories()} returned.
 */
public interface ProjectSource {

  /** The project's repositories, in the order every tie in the graph is broken by. */
  List<String> repositories();

  /** Every pin the repository holds. */
  List<Pin> consumes(String repository);

  /**
   * Every coordinate the repository publishes. Its own GITLINK coordinate ({@code
   * Coordinate(GITLINK, repository)}) is implied and need not be listed.
   */
  Set<Coordinate> produces(String repository);

  /** What the repository deploys as, empty when it deploys nothing. */
  Optional<Deployment> deployment(String repository);
}
