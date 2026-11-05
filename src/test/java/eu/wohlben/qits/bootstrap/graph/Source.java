package eu.wohlben.qits.bootstrap.graph;

import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** An in-memory {@link ProjectSource}: repositories in insertion order. */
final class Source implements ProjectSource {

  final Map<String, List<Pin>> consumes = new LinkedHashMap<>();
  final Map<String, Set<Coordinate>> produces = new LinkedHashMap<>();
  final Map<String, Deployment> deployments = new LinkedHashMap<>();

  Source repo(String name) {
    consumes.putIfAbsent(name, new ArrayList<>());
    produces.putIfAbsent(name, new LinkedHashSet<>());
    return this;
  }

  Source produces(String name, Ecosystem ecosystem, String coordinate) {
    repo(name).produces.get(name).add(new Coordinate(ecosystem, coordinate));
    return this;
  }

  Source consumes(String name, Ecosystem ecosystem, String coordinate) {
    repo(name)
        .consumes
        .get(name)
        .add(new Pin(new Coordinate(ecosystem, coordinate), "1", "pom.xml", "property"));
    return this;
  }

  Source deploys(String name, String application, String... resources) {
    repo(name).deployments.put(name, new Deployment(application, List.of(resources)));
    return this;
  }

  /** A shorthand: {@code from} pins a maven coordinate {@code upstream} produces. */
  Source edge(String upstream, String downstream) {
    String coordinate = "eu.wohlben.qits:" + upstream;
    return produces(upstream, Ecosystem.MAVEN, coordinate)
        .consumes(downstream, Ecosystem.MAVEN, coordinate);
  }

  ProjectGraph graph() {
    return ProjectGraph.of(this, Map.of());
  }

  @Override
  public List<String> repositories() {
    return List.copyOf(consumes.keySet());
  }

  @Override
  public List<Pin> consumes(String repository) {
    return consumes.get(repository);
  }

  @Override
  public Set<Coordinate> produces(String repository) {
    return produces.get(repository);
  }

  @Override
  public Optional<Deployment> deployment(String repository) {
    return Optional.ofNullable(deployments.get(repository));
  }
}
