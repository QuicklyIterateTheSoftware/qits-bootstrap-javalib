# qits-bootstrap-javalib — working notes

Read `README.md` first: it is the embedder's contract. These are the rules that bite when you change
the jar.

## Plain Java, and why

- **Two embedders, neither of which may inherit anything.** qits-bootstrap-cli links this jar into a
  GraalVM native image; qits-maintenance-service runs it inside Quarkus. A bean annotation here would
  do nothing in one and drag a container into the other.
- **No CDI, no Quarkus, no JDBC, no reflection, no Lombok, no config file.** `NoFrameworkTest` scans
  `src/main/java` as text for `jakarta.`, `javax.inject`, `java.sql`, `io.quarkus`, `org.jboss`,
  `java.lang.reflect` and `lombok` — the word in a comment counts too, so do not write it.
- **Jackson only as a `JsonNode` tree** (`ObjectMapper.readTree`). Data binding needs reflection
  registered in every native embedder; a tree needs none.
- **snakeyaml only loaded into a `Map`**, always as `new Yaml(new SafeConstructor(new
  LoaderOptions()))` (see `YamlDocument`). Never a bean constructor, never a type tag.
- **Runtime dependencies: `jackson-databind` (with its core and annotations) and `snakeyaml`, nothing
  else.** `./mvnw dependency:tree` shows exactly that at compile scope; keep it that way.
  `quarkus-bom` is imported in `dependencyManagement` only, so both align with the versions
  qits-maintenance-service runs on.

## The rules that bite

- **The library never sources a fact.** It does not clone, fetch, dial a git host or read the disk.
  Callers implement `RepositoryFiles` (one repository at one revision) and, later, `ProjectSource`;
  what a failed read means for a stored row (UNREACHABLE, retry) is theirs as well.
- **Discovery is ported, not redesigned.** `ManifestReader` produces what qits-maintenance-service's
  `ManifestScanner` produced from the same files: the root, the reactor, `docker/`, `.gitmodules` +
  gitlinks, one pin per line. A change here changes the maintenance inventory.
- **The graph decides order, never action.** `ProjectGraph` is pure over `ProjectSource`; `Walk`
  offers and the visitor acts. A walk never offers a member of a cycle, so how to break one (for
  example qits-maintenance's one-knot-per-tick rule in `BumpOrder`) belongs to the caller.
  `BumpOrderContractTest` pins down how the walk reproduces `BumpOrder.nextUpTo`'s READY picks.
- **`Coordinate.key()` must stay byte-identical** to `ArtifactGraph.producerKey`
  (`<wire name> <name>`), because the maintenance side indexes its ledger by it.
- **A listed file that will not read is an exception, never fewer pins** (qits-1172): a partial set
  of pins is what the artifact GC would read as "unreferenced".
- **Packages stay under `eu.wohlben.qits.bootstrap.graph`.** No split package with either embedder.
- **Tests are plain JUnit 5** and read only this repository: the `src/test/resources/estate/` files
  are verbatim copies of real `.config/qits/` files, refreshed by copying, never by editing.

## Building

Every maven invocation on the shared host goes through the lock, one JVM at a time:

    flock /tmp/qits-maven.lock env JAVA_TOOL_OPTIONS=-Xmx1g ./mvnw -B -ntp -s .qits-maven-settings.xml verify
