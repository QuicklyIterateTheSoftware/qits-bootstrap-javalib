package eu.wohlben.qits.bootstrap.graph.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The manifest says what is ALLOWED and the lock says what is INSTALLED — and only the second can
 * be compared with a registry's answer.
 */
class NpmParserTest {

  private static final String MANIFEST =
      """
      {"name":"spa",
       "dependencies":{"@angular/core":"^21.0.0","@qits/ui-components":"2026.8.1"},
       "devDependencies":{"typescript":"~5.9.0","unlocked":"^1.0.0"}}
      """;

  private static final String LOCK =
      """
      {"lockfileVersion":3,
       "packages":{
         "":{"name":"spa"},
         "node_modules/@angular/core":{"version":"21.0.4"},
         "node_modules/@qits/ui-components":{"version":"2026.8.1"},
         "node_modules/typescript":{"version":"5.9.2"},
         "node_modules/@angular/core/node_modules/tslib":{"version":"2.8.0"}}}
      """;

  private static Optional<ParsedPin> find(List<ParsedPin> pins, String name) {
    return pins.stream().filter(pin -> pin.name().equals(name)).findFirst();
  }

  @Test
  void theVersionIsTheLocksAndTheRangeIsTheManifests() {
    List<ParsedPin> pins = NpmParser.parse("package.json", MANIFEST, LOCK);
    ParsedPin core = find(pins, "@angular/core").orElseThrow();
    assertEquals("21.0.4", core.version());
    assertEquals("^21.0.0", core.range());
    assertEquals("dependencies", core.location());
  }

  @Test
  void devDependenciesAreReadAndSayWhichBlockTheyAreIn() {
    ParsedPin typescript =
        find(NpmParser.parse("package.json", MANIFEST, LOCK), "typescript").orElseThrow();
    assertEquals("5.9.2", typescript.version());
    assertEquals("devDependencies", typescript.location());
  }

  @Test
  void aDependencyTheLockDoesNotResolveIsNotAPin() {
    assertTrue(find(NpmParser.parse("package.json", MANIFEST, LOCK), "unlocked").isEmpty());
  }

  @Test
  void aNestedInstallPathIsTransitiveAndIsNotRead() {
    assertTrue(find(NpmParser.parse("package.json", MANIFEST, LOCK), "tslib").isEmpty());
  }

  @Test
  void aLockfileV1IsReadByName() {
    String legacy = "{\"lockfileVersion\":1,\"dependencies\":{\"@angular/core\":{\"version\":\"21.0.9\"}}}";
    ParsedPin core =
        find(NpmParser.parse("package.json", MANIFEST, legacy), "@angular/core").orElseThrow();
    assertEquals("21.0.9", core.version());
  }

  @Test
  void withNoLockThereIsNothingToPin() {
    assertTrue(NpmParser.parse("package.json", MANIFEST, null).isEmpty());
  }

  // --- pnpm-lock.yaml -----------------------------------------------------------------------

  private static final String PNPM_MANIFEST =
      """
      {"name":"spa",
       "dependencies":{"@qits/ui-components":"2026.1106.121838","left-over":"workspace:*"},
       "devDependencies":{"@analogjs/vite-plugin-angular":"^2.6.4","unlocked":"^1.0.0"}}
      """;

  private static final String PNPM_LOCK =
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
            left-over:
              specifier: workspace:*
              version: link:../left-over
          devDependencies:
            '@analogjs/vite-plugin-angular':
              specifier: ^2.6.4
              version: 2.6.4(6cfc0391fb3c87d9d0956e87c2618858)

      packages:
        irrelevant: {}
      """;

  @Test
  void pnpmLockResolvesDirectDependenciesAndDevDependencies() {
    List<ParsedPin> pins =
        NpmParser.parse("package.json", PNPM_MANIFEST, PNPM_LOCK, NpmParser.LockKind.PNPM_LOCK);

    ParsedPin uiComponents = find(pins, "@qits/ui-components").orElseThrow();
    assertEquals("2026.1106.121838", uiComponents.version());
    assertEquals("dependencies", uiComponents.location());

    ParsedPin analog = find(pins, "@analogjs/vite-plugin-angular").orElseThrow();
    assertEquals("2.6.4", analog.version(), "the peer suffix starting at '(' is stripped");
    assertEquals("devDependencies", analog.location());
  }

  @Test
  void pnpmLinkDependencyIsNotAPin() {
    List<ParsedPin> pins =
        NpmParser.parse("package.json", PNPM_MANIFEST, PNPM_LOCK, NpmParser.LockKind.PNPM_LOCK);

    assertTrue(find(pins, "left-over").isEmpty());
  }

  @Test
  void pnpmDependencyTheLockDoesNotResolveIsNotAPin() {
    List<ParsedPin> pins =
        NpmParser.parse("package.json", PNPM_MANIFEST, PNPM_LOCK, NpmParser.LockKind.PNPM_LOCK);

    assertTrue(find(pins, "unlocked").isEmpty());
  }

  @Test
  void malformedPnpmLockYieldsNoVersions() {
    List<ParsedPin> pins =
        NpmParser.parse(
            "package.json", PNPM_MANIFEST, "not: [valid: yaml", NpmParser.LockKind.PNPM_LOCK);

    assertTrue(pins.isEmpty());
  }
}
