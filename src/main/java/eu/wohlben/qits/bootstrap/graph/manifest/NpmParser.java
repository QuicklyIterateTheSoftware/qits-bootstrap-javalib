package eu.wohlben.qits.bootstrap.graph.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads the direct pins of one {@code package.json}, resolved through its lock.
 *
 * <p><b>The manifest says what is ALLOWED and the lock says what is INSTALLED.</b> A range like
 * {@code ^21.0.0} is not a version and cannot be compared with a registry's latest — three
 * different installs of it are three different trees. So the version on a pin is the lock's
 * resolved one, and the range travels beside it as {@code range}, which is what a reader needs to
 * judge whether a bump is inside what the author allowed.
 *
 * <p><b>A dependency the lock does not resolve is not a pin.</b> It means the lock is out of step
 * with the manifest, and inventing a version from the range would be this service guessing at
 * somebody else's tree.
 *
 * <p><b>Two lock FORMATS, the same rule.</b> Most repositories lock with npm's own
 * {@code package-lock.json}; a couple lock with pnpm's {@code pnpm-lock.yaml} instead and carry no
 * {@code package-lock.json} at all. {@link LockKind} says which shape a lock argument is, because
 * nothing about the text alone says it — one is JSON, the other YAML, and nothing here guesses.
 *
 * <p><b>The nested {@code webui/} package.json is not read here and never reaches this parser</b> —
 * it is a gitlink to its own repository, which the catalog lists separately. See {@link
 * ManifestReader}, which is where the discovery stops.
 */
public final class NpmParser {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The two blocks that hold direct dependencies, and the location a pin records. */
  private static final List<String> BLOCKS = List.of("dependencies", "devDependencies");

  /**
   * The three blocks a pnpm {@code importers} entry may hold. {@code optionalDependencies} is a
   * third block npm itself also has, but no root manifest on this platform uses it for a
   * {@code package-lock.json} repository yet, so {@link #BLOCKS} stays the two it does.
   */
  private static final List<String> PNPM_BLOCKS =
      List.of("dependencies", "devDependencies", "optionalDependencies");

  private NpmParser() {}

  /** Which shape a lock argument to {@link #parse(String, String, String, LockKind)} is. */
  public enum LockKind {
    /** npm's own {@code package-lock.json}: JSON, keyed by install path or by name. */
    PACKAGE_LOCK,
    /** pnpm's {@code pnpm-lock.yaml}: YAML, keyed by importer. */
    PNPM_LOCK
  }

  /**
   * The pins of one package.json, resolved through a {@code package-lock.json}.
   *
   * @param manifestPath the manifest's repository-relative path
   * @param packageJson the manifest
   * @param packageLockJson its lock, or null when the repository carries none
   */
  public static List<ParsedPin> parse(String manifestPath, String packageJson, String packageLockJson) {
    return parse(manifestPath, packageJson, packageLockJson, LockKind.PACKAGE_LOCK);
  }

  /**
   * The pins of one package.json, resolved through whichever lock the repository carries.
   *
   * @param manifestPath the manifest's repository-relative path
   * @param packageJson the manifest
   * @param lock the lock's text, or null when the repository carries none
   * @param lockKind which shape {@code lock} is; ignored when {@code lock} is null
   */
  public static List<ParsedPin> parse(
      String manifestPath, String packageJson, String lock, LockKind lockKind) {
    JsonNode manifest = read(packageJson);
    if (manifest == null) {
      return List.of();
    }
    Map<String, String> locked =
        lock == null
            ? Map.of()
            : lockKind == LockKind.PNPM_LOCK ? lockedVersionsPnpm(lock) : lockedVersions(lock);
    List<ParsedPin> pins = new ArrayList<>();
    for (String block : BLOCKS) {
      JsonNode dependencies = manifest.get(block);
      if (dependencies == null || !dependencies.isObject()) {
        continue;
      }
      Iterator<Map.Entry<String, JsonNode>> fields = dependencies.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String name = entry.getKey();
        JsonNode range = entry.getValue();
        if (!range.isTextual()) {
          continue;
        }
        String version = locked.get(name);
        if (version == null || version.isBlank()) {
          continue;
        }
        pins.add(
            ParsedPin.of(Ecosystem.NPM, manifestPath, name, version, range.asText(), block));
      }
    }
    return List.copyOf(pins);
  }

  /**
   * The lock's resolved version per package name.
   *
   * <p><b>Two lock shapes.</b> Lockfile v2 and v3 key {@code packages} by an install PATH, so a
   * top-level dependency is {@code node_modules/<name>} — a nested one is
   * {@code node_modules/a/node_modules/b} and is deliberately not read: it is transitive. Lockfile
   * v1 keys {@code dependencies} by name directly, and is read as the fallback.
   */
  static Map<String, String> lockedVersions(String packageLockJson) {
    JsonNode lock = read(packageLockJson);
    if (lock == null) {
      return Map.of();
    }
    Map<String, String> versions = new java.util.LinkedHashMap<>();
    JsonNode packages = lock.get("packages");
    if (packages != null && packages.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = packages.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String path = entry.getKey();
        if (!path.startsWith("node_modules/")) {
          continue;
        }
        String name = path.substring("node_modules/".length());
        if (name.contains("/node_modules/")) {
          continue;
        }
        JsonNode version = entry.getValue().get("version");
        if (version != null && version.isTextual()) {
          versions.put(name, version.asText());
        }
      }
    }
    JsonNode legacy = lock.get("dependencies");
    if (versions.isEmpty() && legacy != null && legacy.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = legacy.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        JsonNode version = entry.getValue().get("version");
        if (version != null && version.isTextual()) {
          versions.put(entry.getKey(), version.asText());
        }
      }
    }
    return versions;
  }

  /**
   * The pnpm lock's resolved version per package name, read from its {@code importers['.']} entry.
   *
   * <p><b>pnpm resolves the ROOT project as an importer, not a {@code packages} map keyed like
   * npm's.</b> {@code importers['.'].<block>.<name>} carries both a {@code specifier} (the
   * manifest's own range, already available from the manifest itself) and a {@code version} —
   * the installed one, with a peer-dependency suffix tacked on in parentheses, for example
   * {@code 2.6.4(6cfc0391fb3c87d9d0956e87c2618858)}. Only what comes before the first {@code (} is
   * a version.
   *
   * <p><b>Not every {@code version} is a version.</b> A workspace sibling resolves to
   * {@code link:../other-package} and a tarball dependency to {@code file:../thing.tgz}; neither
   * is a registry release, and nothing else that does not start with a digit is either, so both
   * are skipped the same way a lock entry npm never resolved is.
   *
   * <p><b>Malformed YAML yields no versions</b>, the same treatment a malformed
   * {@code package-lock.json} gets from {@link #lockedVersions}: a lock that will not parse pins
   * nothing rather than guessing.
   */
  static Map<String, String> lockedVersionsPnpm(String pnpmLockYaml) {
    Map<?, ?> top;
    try {
      top = YamlDocument.mapping("pnpm-lock.yaml", pnpmLockYaml);
    } catch (RuntimeException malformed) {
      return Map.of();
    }
    Object importersValue = top.get("importers");
    if (!(importersValue instanceof Map<?, ?> importers)) {
      return Map.of();
    }
    Object rootValue = importers.get(".");
    if (!(rootValue instanceof Map<?, ?> rootImporter)) {
      return Map.of();
    }
    Map<String, String> versions = new java.util.LinkedHashMap<>();
    for (String block : PNPM_BLOCKS) {
      Object blockValue = rootImporter.get(block);
      if (!(blockValue instanceof Map<?, ?> dependencies)) {
        continue;
      }
      for (Map.Entry<?, ?> entry : dependencies.entrySet()) {
        if (!(entry.getValue() instanceof Map<?, ?> fields)) {
          continue;
        }
        Object versionValue = fields.get("version");
        if (versionValue == null) {
          continue;
        }
        String version = String.valueOf(versionValue);
        int peer = version.indexOf('(');
        if (peer >= 0) {
          version = version.substring(0, peer);
        }
        if (version.isEmpty() || !Character.isDigit(version.charAt(0))) {
          // link:, file:, or anything else a registry never published — not a pin.
          continue;
        }
        versions.putIfAbsent(String.valueOf(entry.getKey()), version);
      }
    }
    return versions;
  }

  private static JsonNode read(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(json);
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }
}
