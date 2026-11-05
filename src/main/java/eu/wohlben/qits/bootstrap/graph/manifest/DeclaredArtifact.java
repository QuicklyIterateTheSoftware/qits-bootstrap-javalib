package eu.wohlben.qits.bootstrap.graph.manifest;

/**
 * One entry of {@code artifacts:} in {@code .config/qits/release.yml}: what a release of the
 * repository publishes.
 *
 * @param type the artifact type, {@code maven}, {@code docker}, {@code npm}, {@code docs}, …
 * @param name the coordinate in that type's own spelling, e.g. {@code eu.wohlben.qits:qits-ci}
 * @param path the module directory it is built from, repository-relative; null for the root
 */
public record DeclaredArtifact(String type, String name, String path) {}
