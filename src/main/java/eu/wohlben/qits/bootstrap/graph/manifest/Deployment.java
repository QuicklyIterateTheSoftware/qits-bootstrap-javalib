package eu.wohlben.qits.bootstrap.graph.manifest;

import java.util.List;

/**
 * What {@code .config/qits/deployments.yml} says a repository deploys as.
 *
 * @param application the {@code application:} value, null when the file does not name one (the
 *     deployer then derives it from the repository)
 * @param resources the {@code resources:} entries, e.g. {@code postgresql:db} or {@code
 *     idp:client}, in file order; empty when absent
 */
public record Deployment(String application, List<String> resources) {

  public Deployment {
    resources = resources == null ? List.of() : List.copyOf(resources);
  }
}
