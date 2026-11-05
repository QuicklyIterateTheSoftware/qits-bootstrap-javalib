package eu.wohlben.qits.bootstrap.graph;

import java.util.Objects;

/**
 * Something a repository can publish and another can pin: an ecosystem and a name in that
 * ecosystem's own spelling ({@code eu.wohlben.qits:qits-eventstream}, {@code qits/qits-ci}, or for
 * a GITLINK the submodule's repository name).
 *
 * @param ecosystem which world the name lives in
 * @param name the name, verbatim
 */
public record Coordinate(Ecosystem ecosystem, String name) {

  public Coordinate {
    Objects.requireNonNull(ecosystem, "a coordinate needs an ecosystem");
    Objects.requireNonNull(name, "a coordinate needs a name");
  }

  /**
   * {@code <wire name> <name>} — byte-identical to qits-maintenance's {@code
   * ArtifactGraph.producerKey(ecosystem, name)}, so either side's keys index the other's maps.
   */
  public String key() {
    return ecosystem.wireName() + " " + name;
  }
}
