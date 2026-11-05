package eu.wohlben.qits.bootstrap.graph;

/** What a {@link Walk} offers each repository to. */
@FunctionalInterface
public interface Visitor {

  /** Act on {@code repository}; never null. */
  Verdict visit(String repository);
}
