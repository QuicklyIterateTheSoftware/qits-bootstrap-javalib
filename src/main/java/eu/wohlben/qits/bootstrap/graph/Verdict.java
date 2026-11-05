package eu.wohlben.qits.bootstrap.graph;

/** A {@link Visitor}'s answer about one repository in a {@link Walk}. */
public enum Verdict {
  /** Finished: its downstreams may be offered. */
  DONE,
  /** Not finished and not to be offered again; its downstreams wait. The walk goes on. */
  HELD,
  /** Stop the walk here. */
  FAILED,
  /** Stop the walk here: the graph has to be recomputed before anything else is offered. */
  RECOMPUTE
}
