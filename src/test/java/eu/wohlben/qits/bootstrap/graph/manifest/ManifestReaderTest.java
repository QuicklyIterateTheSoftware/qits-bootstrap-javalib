package eu.wohlben.qits.bootstrap.graph.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The discovery of {@link ManifestReader}: which files are read, and the pins they produce.
 *
 * <p>Ported from qits-maintenance-service's {@code ManifestScannerTest}, every case that proves
 * discovery or pin production from file contents alone. Only the harness changed: the git host
 * answering out of trees and blobs is an in-memory {@link RepositoryFiles} here, and a scan's
 * UNREACHABLE row is the exception the reader throws instead.
 */
class ManifestReaderTest {

  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";
  private static final String PINNED = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";

  private static final String GITMODULES =
      """
      [submodule "qits-artifacts-frontend"]
      \tpath = service/src/main/webui
      \turl = ../qits-artifacts-frontend.git
      \tignore = all
      """;

  /**
   * One revision's files: the listed paths, the contents a read answers with, and the gitlinks.
   * Every read is recorded, so a case can prove which reads were not made.
   */
  private static final class Files implements RepositoryFiles {

    final Set<String> paths = new LinkedHashSet<>();
    final Map<String, String> contents = new LinkedHashMap<>();
    final Map<String, String> gitlinks = new LinkedHashMap<>();
    /** A read that fails, whatever the listing says. */
    final Map<String, RuntimeException> failing = new LinkedHashMap<>();
    final List<String> read = new ArrayList<>();
    int gitlinkReads;

    /** A file the listing names, with its content. */
    Files file(String path, String content) {
      paths.add(path);
      contents.put(path, content);
      return this;
    }

    /** A file the listing names and a read does not answer for. */
    Files listedOnly(String path) {
      paths.add(path);
      return this;
    }

    /** Content readable at a path no listing named — a module pom asked for by path. */
    Files unlisted(String path, String content) {
      contents.put(path, content);
      return this;
    }

    Files gitlink(String path, String sha) {
      gitlinks.put(path, sha);
      return this;
    }

    Files failing(String path, RuntimeException failure) {
      failing.put(path, failure);
      return this;
    }

    @Override
    public Optional<String> read(String path) {
      read.add(path);
      if (failing.containsKey(path)) {
        throw failing.get(path);
      }
      return Optional.ofNullable(contents.get(path));
    }

    @Override
    public List<String> paths() {
      return List.copyOf(paths);
    }

    @Override
    public Map<String, String> gitlinks() {
      gitlinkReads++;
      return Map.copyOf(gitlinks);
    }
  }

  private static List<ParsedPin> read(Files files) {
    return ManifestReader.read(files);
  }

  /** A gitlink with a sha: the submodule is one pin. */
  @Test
  void aSubmoduleWhoseGitlinkShaIsKnownIsOnePin() {
    Files files =
        new Files().file(GitmodulesParser.PATH, GITMODULES).gitlink("service/src/main/webui", PINNED);

    List<ParsedPin> pins = read(files);

    assertEquals(1, pins.size());
    ParsedPin pin = pins.get(0);
    assertEquals(Ecosystem.GITLINK, pin.ecosystem());
    assertEquals("qits-artifacts-frontend", pin.name(), "the name is the url's, not the entry's");
    assertEquals(PINNED, pin.version(), "the version of a gitlink is the commit its tree entry records");
    assertEquals("service/src/main/webui", pin.manifestPath());
    assertEquals("gitlink:service/src/main/webui", pin.location());
  }

  /**
   * No sha, no pin: a path that is a plain file rather than a gitlink, or a gitlink whose sha is
   * blank, produces nothing rather than a guessed version.
   */
  @Test
  void noGitlinkShaProducesNoGitlinkPinRatherThanAGuessedOne() {
    Files plainFile =
        new Files().file(GitmodulesParser.PATH, GITMODULES).file("service/src/main/webui", "");
    assertTrue(read(plainFile).isEmpty());

    Files blankSha =
        new Files().file(GitmodulesParser.PATH, GITMODULES).gitlink("service/src/main/webui", " ");
    assertTrue(read(blankSha).isEmpty());
  }

  /** A section left behind by a commit that removed the submodule names nothing in the tree. */
  @Test
  void aDeclarationTheTreeDoesNotHoldIsSkipped() {
    Files files =
        new Files()
            .file(GitmodulesParser.PATH, GITMODULES)
            .gitlink("service/src/main/something-else", PINNED);

    assertTrue(read(files).isEmpty());
  }

  /** No file, no reads: a repository without submodules costs nothing. */
  @Test
  void aRepositoryWithNoGitmodulesIsNotAskedForOne() {
    Files files = new Files().file("README.md", "# hello\n");

    assertTrue(read(files).isEmpty());
    assertTrue(
        files.read.stream().noneMatch(GitmodulesParser.PATH::equals),
        "the listing already says the file is not there");
    assertEquals(0, files.gitlinkReads);
  }

  /** Several submodules under one directory: one pin each, in file order, one gitlinks() call. */
  @Test
  void submodulesSharingADirectoryArePinnedInFileOrder() {
    Files files =
        new Files()
            .file(
                GitmodulesParser.PATH,
                """
                [submodule "qits-ci-service"]
                \tpath = components/qits-ci/qits-ci-service
                \turl = ../qits-ci-service.git
                [submodule "qits-ci-frontend"]
                \tpath = components/qits-ci/qits-ci-frontend
                \turl = ../qits-ci-frontend.git
                """)
            .gitlink("components/qits-ci/qits-ci-frontend", SHA)
            .gitlink("components/qits-ci/qits-ci-service", PINNED);

    List<ParsedPin> pins = read(files);

    assertEquals(2, pins.size());
    assertEquals(
        List.of("qits-ci-service", "qits-ci-frontend"), pins.stream().map(ParsedPin::name).toList());
    assertEquals(1, files.gitlinkReads, "one revision, one gitlinks answer");
  }

  /** The submodule walk stops at 128 pins. */
  @Test
  void theSubmoduleWalkIsCappedAt128Pins() {
    StringBuilder gitmodules = new StringBuilder();
    Files files = new Files();
    for (int index = 0; index < 130; index++) {
      gitmodules
          .append("[submodule \"m")
          .append(index)
          .append("\"]\n\tpath = c/m")
          .append(index)
          .append("\n\turl = ../m")
          .append(index)
          .append(".git\n");
      files.gitlink("c/m" + index, PINNED);
    }
    files.file(GitmodulesParser.PATH, gitmodules.toString());

    List<ParsedPin> pins = read(files);

    assertEquals(128, pins.size());
    assertEquals("m127", pins.get(127).name());
  }

  // --- docker: the root, and the one directory below it ---------------------------------------

  /**
   * THE BASE IMAGE OF A DAEMON IS PINNED IN AN ARG, IN A DIRECTORY. qits-workspace-daemon builds
   * from {@code docker/Dockerfile} and pins its base in an {@code ARG} default rather than on the
   * FROM.
   */
  @Test
  void aDockerfileInTheDockerDirectoryIsFoundAndItsArgDefaultIsThePin() {
    Files files =
        new Files()
            .file(
                "docker/Dockerfile",
                """
                ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:2026.902.143920
                FROM ${WORKSPACE_BASE}
                """);

    List<ParsedPin> pins = read(files);

    assertEquals(1, pins.size());
    ParsedPin pin = pins.get(0);
    assertEquals(Ecosystem.DOCKER, pin.ecosystem());
    assertEquals("qits/workspace-base", pin.name(), "the host is an address, not part of the name");
    assertEquals("2026.902.143920", pin.version());
    assertEquals("docker/Dockerfile", pin.manifestPath(), "the path the bump step edits");
    assertEquals("arg:WORKSPACE_BASE", pin.location());
  }

  /** The other spelling of a qualified build file, which qits-projects-daemon uses. */
  @Test
  void aQualifiedDockerfileIsReadUnderEitherSpelling() {
    Files files =
        new Files()
            .file("docker/Dockerfile.projects", "ARG BASE=qits/workspace-base:2026.902.143920\n")
            .file("node.Dockerfile", "FROM qits/build-images/node-base:2026.813.1\n");

    assertEquals(
        List.of("node.Dockerfile", "docker/Dockerfile.projects"),
        read(files).stream().map(ParsedPin::manifestPath).toList());
  }

  /** Only the root and docker/ itself: a Dockerfile deeper down, or elsewhere, is not read. */
  @Test
  void aDockerfileOutsideTheRootAndDockerDirectoryIsNotRead() {
    Files files =
        new Files()
            .file("Dockerfile", "FROM qits/build-images/maven-base:2026.813.1\n")
            .file("docker/nested/Dockerfile", "FROM qits/nested:1\n")
            .file("service/Dockerfile", "FROM qits/service:1\n");

    assertEquals(1, read(files).size());
    assertEquals(List.of("Dockerfile"), files.read);
  }

  // --- ignore: an ecosystem taken off the reading ------------------------------------------------

  /** A repository carrying both a gitlink and a Dockerfile. */
  private static Files mixed() {
    return new Files()
        .file(GitmodulesParser.PATH, GITMODULES)
        .file("Dockerfile", "FROM qits/build-images/maven-base:2026.813.1\n")
        .gitlink("service/src/main/webui", PINNED);
  }

  /** The control: nothing ignored, both ecosystems pin. */
  @Test
  void withoutAnIgnoreBothEcosystemsPin() {
    assertEquals(
        List.of(Ecosystem.DOCKER, Ecosystem.GITLINK),
        read(mixed()).stream().map(ParsedPin::ecosystem).toList());
  }

  /** AN IGNORED ECOSYSTEM'S PINS SIMPLY DO NOT EXIST, and the reads that would find one are not made. */
  @Test
  void anIgnoredEcosystemIsNotReadWhileTheOthersStillPin() {
    Files files = mixed();

    List<ParsedPin> pins = ManifestReader.read(files, Set.of(Ecosystem.GITLINK));

    assertEquals(1, pins.size());
    assertEquals(Ecosystem.DOCKER, pins.get(0).ecosystem());
    assertTrue(
        files.read.stream().noneMatch(GitmodulesParser.PATH::equals),
        "an ignored ecosystem costs no read at all");
  }

  /** Every ecosystem may be named, and a repository that names them all pins nothing. */
  @Test
  void everyEcosystemMayBeIgnored() {
    assertTrue(ManifestReader.read(mixed(), Set.of(Ecosystem.values())).isEmpty());
  }

  /** A file git wrote: an unparseable one yields no pins, and no failure. */
  @Test
  void anUnparseableGitmodulesYieldsNoPins() {
    Files files = new Files().file(GitmodulesParser.PATH, "]]] this is not a config file [[[");

    assertTrue(read(files).isEmpty());
  }

  // --- a manifest the listing names and the read does not answer (qits-1172) -----------------

  private static final String ROOT_POM =
      """
      <project>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-artifacts</artifactId>
        <version>2026.821.1</version>
        <modules>
          <module>service</module>
        </modules>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>2026.811.1</version>
          </dependency>
        </dependencies>
      </project>
      """;

  /** No partial set: a listed pom that fails to read fails the whole reading. */
  @Test
  void aListedPomThatFailsToReadFailsTheReading() {
    RuntimeException reset = new RuntimeException("connection reset");
    Files files =
        new Files()
            .listedOnly("pom.xml")
            .failing("pom.xml", reset)
            .file("Dockerfile", "FROM qits/build-images/maven-base:2026.813.1\n");

    assertEquals(reset, assertThrows(RuntimeException.class, () -> read(files)));
  }

  /** A listed manifest a read answers empty for is a contradiction, not an absence. */
  @Test
  void aListedManifestReadAsAbsentFailsTheReading() {
    Files files =
        new Files()
            .file("package.json", "{\"dependencies\":{\"@qits/ui-components\":\"2026.8.1\"}}")
            .listedOnly(ManifestReader.PACKAGE_LOCK);

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> read(files));
    assertTrue(failure.getMessage().contains(ManifestReader.PACKAGE_LOCK), failure.getMessage());
  }

  /** The same for a Dockerfile. */
  @Test
  void aListedDockerfileReadAsAbsentFailsTheReading() {
    Files files = new Files().listedOnly("Dockerfile");

    assertThrows(IllegalStateException.class, () -> read(files));
  }

  /**
   * A module the pom NAMES and the revision does not carry is genuinely absent, and the root's pins
   * stand. A module pom whose read fails fails the reading.
   */
  @Test
  void anAbsentModulePomIsSkippedWhileAFailingOneFailsTheReading() {
    Files absent = new Files().file("pom.xml", ROOT_POM);
    assertEquals(
        List.of("eu.wohlben.qits:qits-eventstream"),
        read(absent).stream().map(ParsedPin::name).toList());

    RuntimeException unreachable = new RuntimeException("502");
    Files failing = new Files().file("pom.xml", ROOT_POM).failing("service/pom.xml", unreachable);
    assertEquals(unreachable, assertThrows(RuntimeException.class, () -> read(failing)));
  }

  // --- the reactor -------------------------------------------------------------------------------

  /**
   * The ROOT pom's properties reach every module, the reactor's own coordinates are reactorOwn, a
   * module's parent that is the root is no pin, and one line read by every module is one pin.
   */
  @Test
  void theReactorIsReadWholeBeforeItIsJudged() {
    Files files =
        new Files()
            .file(
                "pom.xml",
                """
                <project>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-ci-parent</artifactId>
                  <version>2026.821.1</version>
                  <properties><eventstream.version>2026.811.1</eventstream.version></properties>
                  <modules><module>domain</module><module>./service/</module></modules>
                </project>
                """)
            .unlisted(
                "domain/pom.xml",
                """
                <project>
                  <parent>
                    <groupId>eu.wohlben.qits</groupId>
                    <artifactId>qits-ci-parent</artifactId>
                    <version>2026.821.1</version>
                  </parent>
                  <artifactId>qits-ci-domain</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-eventstream</artifactId>
                      <version>${eventstream.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """)
            .unlisted(
                "service/pom.xml",
                """
                <project>
                  <parent>
                    <groupId>eu.wohlben.qits</groupId>
                    <artifactId>qits-ci-parent</artifactId>
                    <version>2026.821.1</version>
                  </parent>
                  <artifactId>qits-ci</artifactId>
                  <modules><module>src/main/webui</module></modules>
                  <dependencies>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-ci-domain</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-eventstream</artifactId>
                      <version>${eventstream.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

    List<ParsedPin> pins = read(files);

    assertEquals(
        List.of("eu.wohlben.qits:qits-eventstream", "eu.wohlben.qits:qits-ci-domain"),
        pins.stream().map(ParsedPin::name).toList(),
        "no parent pin for the reactor's own root, and one eventstream line");
    assertEquals("2026.811.1", pins.get(0).version(), "the root's property reached the module");
    assertEquals("pom.xml", pins.get(0).manifestPath(), "the line is the root's property");
    assertTrue(pins.get(1).reactorOwn());
    assertTrue(
        files.read.stream().noneMatch(path -> path.contains("src/main/webui")),
        "an embedded client's pom is never followed");
  }

  /** The root package.json is read through the lock beside it. */
  @Test
  void theRootPackageJsonIsReadThroughItsLock() {
    Files files =
        new Files()
            .file("package.json", "{\"dependencies\":{\"@qits/ui-components\":\"^2026.8.1\"}}")
            .file(
                ManifestReader.PACKAGE_LOCK,
                "{\"packages\":{\"node_modules/@qits/ui-components\":{\"version\":\"2026.8.3\"}}}")
            .file("webui/package.json", "{\"dependencies\":{\"left-pad\":\"1.0.0\"}}");

    List<ParsedPin> pins = read(files);

    assertEquals(1, pins.size());
    assertEquals(Ecosystem.NPM, pins.get(0).ecosystem());
    assertEquals("@qits/ui-components", pins.get(0).name());
    assertEquals("2026.8.3", pins.get(0).version());
    assertEquals("^2026.8.1", pins.get(0).range());
  }

  // --- pnpm-lock.yaml: the two jslibs that carry no package-lock.json at all ------------------

  private static final String PNPM_LOCK_YAML =
      """
      lockfileVersion: '9.0'

      settings:
        autoInstallPeers: true
        excludeLinksFromLockfile: false

      importers:

        .:
          dependencies:
            '@qits/ui-components':
              specifier: 2026.1106.121838
              version: 2026.1106.121838
          devDependencies:
            '@analogjs/vite-plugin-angular':
              specifier: ^2.6.4
              version: 2.6.4(6cfc0391fb3c87d9d0956e87c2618858)
      """;

  /** A repository with no package-lock.json at all — the two jslibs — is still read. */
  @Test
  void aRootPackageJsonWithNoPackageLockIsReadThroughPnpmLock() {
    Files files =
        new Files()
            .file(
                "package.json",
                """
                {"dependencies":{"@qits/ui-components":"2026.1106.121838"},
                 "devDependencies":{"@analogjs/vite-plugin-angular":"^2.6.4"}}
                """)
            .file(ManifestReader.PNPM_LOCK, PNPM_LOCK_YAML);

    List<ParsedPin> pins = read(files);

    assertEquals(2, pins.size());
    ParsedPin uiComponents =
        pins.stream().filter(p -> p.name().equals("@qits/ui-components")).findFirst().orElseThrow();
    assertEquals("2026.1106.121838", uiComponents.version());
    ParsedPin analog =
        pins.stream()
            .filter(p -> p.name().equals("@analogjs/vite-plugin-angular"))
            .findFirst()
            .orElseThrow();
    assertEquals("2.6.4", analog.version(), "the peer suffix starting at '(' is stripped");
  }

  /** package-lock.json keeps precedence when a repository carries both. */
  @Test
  void packageLockWinsOverPnpmLockWhenBothExist() {
    Files files =
        new Files()
            .file("package.json", "{\"dependencies\":{\"@qits/ui-components\":\"^2026.8.1\"}}")
            .file(
                ManifestReader.PACKAGE_LOCK,
                "{\"packages\":{\"node_modules/@qits/ui-components\":{\"version\":\"2026.8.3\"}}}")
            .file(ManifestReader.PNPM_LOCK, PNPM_LOCK_YAML);

    List<ParsedPin> pins = read(files);

    assertEquals(1, pins.size());
    assertEquals("2026.8.3", pins.get(0).version(), "package-lock.json answered, so pnpm's was never read");
    assertTrue(files.read.stream().noneMatch(ManifestReader.PNPM_LOCK::equals));
  }

  /** A pnpm-lock.yaml that will not parse pins nothing rather than failing the whole reading. */
  @Test
  void aMalformedPnpmLockYieldsNoNpmPinsRatherThanFailing() {
    Files files =
        new Files()
            .file("package.json", "{\"dependencies\":{\"@qits/ui-components\":\"^2026.8.1\"}}")
            .file(ManifestReader.PNPM_LOCK, "not: [valid: yaml");

    assertTrue(read(files).isEmpty());
  }

  /** A pnpm-lock.yaml the listing names and the read does not answer fails the reading. */
  @Test
  void aListedPnpmLockReadAsAbsentFailsTheReading() {
    Files files =
        new Files()
            .file("package.json", "{\"dependencies\":{\"@qits/ui-components\":\"2026.8.1\"}}")
            .listedOnly(ManifestReader.PNPM_LOCK);

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> read(files));
    assertTrue(failure.getMessage().contains(ManifestReader.PNPM_LOCK), failure.getMessage());
  }
}
