package eu.wohlben.qits.bootstrap.graph.manifest;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every pin one repository's manifests declare, read from its files at one revision.
 *
 * <p>The discovery qits-maintenance-service's {@code ManifestScanner} ran over the git host, with
 * the git host taken out: the caller hands in a {@link RepositoryFiles} and keeps everything that is
 * about where the files came from and what a failure to read one means for a stored row.
 *
 * <p><b>Discovery is the repository ROOT plus the reactor, plus one named directory.</b> The
 * manifests in the root are taken, and a root pom's declared modules are followed for their poms —
 * transitively, because a module may name modules of its own. The one addition is {@code docker/},
 * which is where several repositories keep the build file the root does not hold. Nothing else is
 * walked: a recursive search would read every vendored file in a repository for a handful of pins.
 *
 * <p><b>{@code service/src/main/webui} is NOT read, and the shape of the discovery is what keeps it
 * out.</b> It is a gitlink to the SPA's own repository, which is read in its own right; reading it
 * here would double every pin it holds and attribute the copy to the wrong repository. The module
 * walk refuses such a path outright, so a pom that ever named one still cannot pull it in.
 *
 * <p><b>The gitlink ITSELF is a pin, and that is a different fact from what it contains.</b>
 * {@code .gitmodules} plus the gitlink's sha say which repository is embedded and at which commit —
 * one line this repository owns and can move. Its contents belong to the submodule.
 *
 * <p><b>A file {@link RepositoryFiles#paths()} lists and {@link RepositoryFiles#read} does not
 * answer is a contradiction, not an absence</b>, and is an {@link IllegalStateException}: a partial
 * set of pins is exactly what must never stand for a revision (qits-1172). A module pom or a
 * gitlink path that no listing named is genuinely absent and is skipped.
 */
public final class ManifestReader {

  /** The lock beside a root package.json. Without it a manifest has ranges and no versions. */
  public static final String PACKAGE_LOCK = "package-lock.json";

  /** What a gitlink pin's location says: the path the {@code 160000} entry sits at. */
  public static final String GITLINK_LOCATION = "gitlink:";

  /** A cap on the reactor walk. A pom that names itself, or a cycle through two modules, would
   * otherwise be an unbounded read. */
  private static final int MAX_POMS = 128;

  /** A cap on the submodule walk. A wrapper repository declares dozens and nothing declares this
   * many; the bound is what keeps one malformed file from being an unbounded read. */
  private static final int MAX_GITLINKS = 128;

  /** The path fragment a gitlink to an embedded SPA sits at on this platform. */
  private static final String EMBEDDED_CLIENT = "src/main/webui";

  /** The one directory below the root that a Dockerfile is looked for in. */
  private static final String DOCKER_DIRECTORY = "docker";

  /** What PomParser writes as the location of a {@code <parent>} pin. */
  private static final String PARENT_LOCATION = "parent:";

  private ManifestReader() {}

  /** Every pin, in discovery order: maven, npm, docker, gitlink — one per (manifest, name, line). */
  public static List<ParsedPin> read(RepositoryFiles files) {
    return read(files, Set.of());
  }

  /**
   * Every pin of every ecosystem not in {@code ignored}.
   *
   * <p>An ignored ecosystem is not filtered afterwards: its files are never read, so a repository
   * that takes an ecosystem off its own reading costs no read for it either.
   */
  public static List<ParsedPin> read(RepositoryFiles files, Set<Ecosystem> ignored) {
    Set<String> root = new LinkedHashSet<>();
    List<String> paths = files.paths();
    for (String path : paths) {
      if (path.indexOf('/') < 0) {
        root.add(path);
      }
    }
    List<ParsedPin> pins = new ArrayList<>();
    if (!ignored.contains(Ecosystem.MAVEN)) {
      pins.addAll(mavenPins(files, root));
    }
    if (!ignored.contains(Ecosystem.NPM)) {
      pins.addAll(npmPins(files, root));
    }
    if (!ignored.contains(Ecosystem.DOCKER)) {
      pins.addAll(dockerPins(files, paths));
    }
    if (!ignored.contains(Ecosystem.GITLINK)) {
      pins.addAll(gitlinkPins(files, root));
    }
    // ONE ROW PER LINE. Every module of a reactor names the same root property, and each one
    // produced a pin against the root pom above. They are one line and one change.
    return dedupe(pins);
  }

  /**
   * The root pom and every pom the reactor reaches from it.
   *
   * <p>The ROOT pom's properties are carried into every module, because a module's version
   * expression is nearly always defined up there. <b>The whole reactor is read BEFORE any of it is
   * parsed</b>: "is this dependency one of our own modules" cannot be answered from a single pom.
   */
  private static List<ParsedPin> mavenPins(RepositoryFiles files, Set<String> root) {
    if (!root.contains("pom.xml")) {
      return List.of();
    }
    String rootPom = listed(files, "pom.xml");

    // --- pass one: read the reactor ------------------------------------------------------------
    Map<String, String> poms = new LinkedHashMap<>();
    poms.put("pom.xml", rootPom);
    Deque<String> pending = new ArrayDeque<>();
    for (String module : PomParser.modules(rootPom)) {
      pending.add(modulePom("", module));
    }
    while (!pending.isEmpty() && poms.size() < MAX_POMS) {
      String path = pending.poll();
      if (path == null || poms.containsKey(path)) {
        continue;
      }
      if (path.contains(EMBEDDED_CLIENT)) {
        continue;
      }
      Optional<String> pom = files.read(path);
      if (pom.isEmpty()) {
        // A module the pom NAMES and the revision does not carry: nothing at it to pin. Maven
        // would refuse to build such a reactor; that is the repository's to fix.
        continue;
      }
      poms.put(path, pom.get());
      String directory = path.substring(0, path.length() - "pom.xml".length());
      for (String module : PomParser.modules(pom.get())) {
        pending.add(modulePom(directory, module));
      }
    }

    // Every coordinate this repository builds. A dependency on one of these is the repository
    // depending on itself, and a PARENT that is one of these is not a dependency at all.
    Set<String> reactor = new LinkedHashSet<>();
    for (String content : poms.values()) {
      PomParser.coordinate(content).ifPresent(reactor::add);
    }

    // --- pass two: parse, and judge each pin against the reactor -------------------------------
    Map<String, String> rootProperties = PomParser.properties(rootPom);
    List<ParsedPin> pins = new ArrayList<>();
    for (Map.Entry<String, String> pom : poms.entrySet()) {
      Map<String, String> inherited = pom.getKey().equals("pom.xml") ? Map.of() : rootProperties;
      for (ParsedPin pin : PomParser.parse(pom.getKey(), pom.getValue(), inherited, "pom.xml")) {
        boolean ownArtifact = reactor.contains(pin.name());
        if (ownArtifact && pin.location().startsWith(PARENT_LOCATION)) {
          // A module inheriting this repository's own root pom is the reactor's shape. A parent
          // from OUTSIDE the reactor is not in this set and stays a pin.
          continue;
        }
        pins.add(pin.withReactorOwn(pin.reactorOwn() || ownArtifact));
      }
    }
    return pins;
  }

  /** A module entry may name a directory or a pom outright, which is maven's own rule. */
  private static String modulePom(String directory, String module) {
    String value = module.trim();
    while (value.startsWith("./")) {
      value = value.substring(2);
    }
    String path = value.endsWith(".xml") ? value : trimTrailingSlash(value) + "/pom.xml";
    return directory.isEmpty() ? path : directory + path;
  }

  private static String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  /** The root package.json, resolved through the lock beside it. */
  private static List<ParsedPin> npmPins(RepositoryFiles files, Set<String> root) {
    if (!root.contains("package.json")) {
      return List.of();
    }
    String manifest = listed(files, "package.json");
    String lock = null;
    if (root.contains(PACKAGE_LOCK)) {
      // The lock is where every npm VERSION comes from: a manifest read without it pins nothing,
      // so an unreadable lock is as unreadable as an unreadable manifest.
      lock = listed(files, PACKAGE_LOCK);
    }
    return NpmParser.parse("package.json", manifest, lock);
  }

  /**
   * Every Dockerfile in the repository root, then every one directly in {@code docker/}, each in
   * the order {@link RepositoryFiles#paths()} lists them.
   */
  private static List<ParsedPin> dockerPins(RepositoryFiles files, List<String> paths) {
    List<String> rootFiles = new ArrayList<>();
    List<String> dockerFiles = new ArrayList<>();
    String prefix = DOCKER_DIRECTORY + "/";
    for (String path : paths) {
      if (path.indexOf('/') < 0) {
        if (isDockerfile(path)) {
          rootFiles.add(path);
        }
      } else if (path.startsWith(prefix)) {
        String name = path.substring(prefix.length());
        if (name.indexOf('/') < 0 && isDockerfile(name)) {
          dockerFiles.add(path);
        }
      }
    }
    List<ParsedPin> pins = new ArrayList<>();
    for (String path : rootFiles) {
      pins.addAll(DockerParser.parse(path, listed(files, path)));
    }
    for (String path : dockerFiles) {
      pins.addAll(DockerParser.parse(path, listed(files, path)));
    }
    return pins;
  }

  /**
   * Every submodule {@code .gitmodules} declares, pinned at the sha its gitlink records.
   *
   * <p><b>The file names the submodule; the TREE holds the version.</b> A declaration with no
   * gitlink sha at its path — a section a removal left behind, a path that is a plain file, or a
   * gitlink the caller cannot report a sha for — produces no pin rather than a guessed one. A
   * {@code .gitmodules} that will not parse is git's file, not a configuration error: what parsed
   * is used.
   */
  private static List<ParsedPin> gitlinkPins(RepositoryFiles files, Set<String> root) {
    if (!root.contains(GitmodulesParser.PATH)) {
      return List.of();
    }
    List<GitmodulesParser.Submodule> modules =
        GitmodulesParser.parse(listed(files, GitmodulesParser.PATH));
    if (modules.isEmpty()) {
      return List.of();
    }
    Map<String, String> gitlinks = files.gitlinks();
    List<ParsedPin> pins = new ArrayList<>();
    for (GitmodulesParser.Submodule module : modules) {
      if (pins.size() >= MAX_GITLINKS) {
        break;
      }
      String pinned = gitlinks.get(module.path());
      if (pinned == null || pinned.isBlank()) {
        continue;
      }
      pins.add(
          new ParsedPin(
              Ecosystem.GITLINK,
              module.path(),
              module.name(),
              pinned.trim(),
              null,
              GITLINK_LOCATION + module.path(),
              false));
    }
    return pins;
  }

  /** One pin per (manifest, name, location) — see the call site for why there are duplicates. */
  private static List<ParsedPin> dedupe(List<ParsedPin> pins) {
    Map<String, ParsedPin> byLine = new LinkedHashMap<>();
    for (ParsedPin pin : pins) {
      byLine.putIfAbsent(pin.manifestPath() + " " + pin.name() + " " + pin.location(), pin);
    }
    return new ArrayList<>(byLine.values());
  }

  /**
   * Both spellings of a qualified build file, because this platform writes both:
   * {@code node.Dockerfile} beside {@code Dockerfile.projects}.
   */
  static boolean isDockerfile(String name) {
    return "Dockerfile".equals(name)
        || name.endsWith(".Dockerfile")
        || name.startsWith("Dockerfile.");
  }

  /** A file the listing named, read — and no answer is a contradiction, not an absence. */
  private static String listed(RepositoryFiles files, String path) {
    return files
        .read(path)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    path + " is listed and could not be read; the pins it holds are unknown"));
  }
}
