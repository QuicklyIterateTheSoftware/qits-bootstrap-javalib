package eu.wohlben.qits.bootstrap.graph;

/** Why one repository sits upstream of another. */
public enum EdgeKind {
  /** The downstream pins a coordinate the upstream publishes: build order. */
  ARTIFACT,

  /** The downstream's deployment declares a resource the upstream's application provides. */
  RUNTIME
}
