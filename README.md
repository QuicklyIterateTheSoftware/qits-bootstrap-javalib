# qits-bootstrap-javalib

**What a repository declares, read from its files**, published as the jar
`eu.wohlben.qits:qits-bootstrap-graph`: the pins its manifests hold, the artifacts its release
publishes and what it deploys as.

    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-bootstrap-graph</artifactId>
        <version><!-- a released calver --></version>
    </dependency>

Plain Java 25. No CDI, no Quarkus, no JDBC, no reflection: static readers over strings and one
interface you implement. The runtime dependencies are `jackson-databind` and `snakeyaml`.

## The two callers

- **qits-bootstrap-cli** links it into a GraalVM native image and answers `RepositoryFiles` from a
  local checkout.
- **qits-maintenance-service** runs it inside Quarkus and answers `RepositoryFiles` from the git
  host's tree and blob routes; the scan's statuses (UNREACHABLE, CONFIG_ERROR), its grouping and its
  catalog stay there.

## The contract

The library never sources a fact. You choose the revision and implement:

```java
public interface RepositoryFiles {
    Optional<String> read(String path);   // file content at that revision, empty when absent
    List<String> paths();                 // every tracked file path
    Map<String, String> gitlinks();       // submodule path -> commit sha
}
```

`ManifestReader.read(files)` (or `read(files, ignoredEcosystems)`) returns every `ParsedPin`, in
discovery order: the root `pom.xml` and its reactor, the root `package.json` through its lock, the
Dockerfiles in the root and in `docker/`, and one GITLINK pin per `.gitmodules` entry with a gitlink
sha. A path `paths()` lists and `read` does not answer is an `IllegalStateException`; an exception
your `read` throws propagates. Either way no partial set is returned.

`ReleaseDeclaration.artifacts(releaseYml)` reads `artifacts:` of `.config/qits/release.yml` into
`DeclaredArtifact(type, name, path)`. `DeploymentDeclaration.parse(deploymentsYml)` reads
`application:` and `resources:` of `.config/qits/deployments.yml` into `Deployment`. Both throw
`IllegalArgumentException` for a file of the wrong shape.

## The project graph

Implement `ProjectSource` (`repositories()`, `consumes`, `produces`, `deployment`) and call
`ProjectGraph.of(source, resourceProviders)`:

- **Nodes** are `repositories()` in order; that order breaks every tie.
- **ARTIFACT edges**: D pins a `Coordinate` that U produces. Every node implicitly produces
  `Coordinate(GITLINK, <its name>)`. A coordinate nobody in the project produces is no edge.
- **RUNTIME edges**: D's `resources:` entry `<kind>[:…]` maps through `resourceProviders` to an
  application that U deploys as. Anything that does not resolve is in `unresolved()`.
- No self edges. When two nodes claim one coordinate or application, the first one wins and the
  tie is in `ambiguous()`.
- `components(kinds)` returns the strongly connected components, upstream ones first.
- `Coordinate.key()` is byte-identical to qits-maintenance's `ArtifactGraph.producerKey`.

`Walk.run(graph, kinds, tieBreak, visitor)` offers repositories one at a time. A repository is
offered once every upstream has answered `DONE`; among those, the lowest under `tieBreak` goes
first. `HELD` blocks only that repository's own downstreams, and `FAILED` or `RECOMPUTE` stops the
walk. Members of a cycle, and everything downstream of one, are never offered. They are reported
in `WalkResult.cycles()` / `unreached()`, and breaking a cycle is the caller's decision.

## Packages

| | |
|---|---|
| `eu.wohlben.qits.bootstrap.graph` | `Ecosystem`, `Coordinate`, `Pin`, `ProjectSource`, `ProjectGraph` (`Edge`, `EdgeKind`), `Walk` (`Visitor`, `Verdict`, `WalkResult`) |
| `eu.wohlben.qits.bootstrap.graph.manifest` | `RepositoryFiles`, `ManifestReader`, `ParsedPin`, the parsers (`PomParser`, `NpmParser`, `DockerParser`, `GitmodulesParser`), `ReleaseDeclaration` / `DeclaredArtifact`, `DeploymentDeclaration` / `Deployment` |

## Building

    flock /tmp/qits-maven.lock env JAVA_TOOL_OPTIONS=-Xmx1g ./mvnw -B -ntp -s .qits-maven-settings.xml verify
