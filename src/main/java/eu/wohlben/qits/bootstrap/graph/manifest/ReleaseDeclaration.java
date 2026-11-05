package eu.wohlben.qits.bootstrap.graph.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code .config/qits/release.yml} — the {@code artifacts:} a repository's release publishes.
 *
 * <p>Only the coordinates are read: {@code type}, {@code name} and the optional {@code path}. Every
 * other key ({@code sbom}, {@code link}, {@code publish}, the archetype, the slots) is the release
 * recipe's and is passed by.
 */
public final class ReleaseDeclaration {

  /** Where the file is, repository-relative. */
  public static final String PATH = ".config/qits/release.yml";

  private ReleaseDeclaration() {}

  /**
   * Every declared artifact, in file order; empty for an empty file or one with no {@code
   * artifacts:}.
   *
   * @throws IllegalArgumentException when the file is not YAML, {@code artifacts:} is not a list, or
   *     an entry is not a mapping carrying a {@code type} and a {@code name}
   */
  public static List<DeclaredArtifact> artifacts(String releaseYml) {
    Object artifacts = YamlDocument.mapping(PATH, releaseYml).get("artifacts");
    if (artifacts == null) {
      return List.of();
    }
    if (!(artifacts instanceof List<?> entries)) {
      throw new IllegalArgumentException(
          PATH + ": artifacts must be a list, not " + YamlDocument.kind(artifacts));
    }
    List<DeclaredArtifact> declared = new ArrayList<>();
    for (int index = 0; index < entries.size(); index++) {
      Object entry = entries.get(index);
      if (!(entry instanceof Map<?, ?> fields)) {
        throw new IllegalArgumentException(
            PATH + ": artifacts[" + index + "] must be a mapping, not " + YamlDocument.kind(entry));
      }
      declared.add(
          new DeclaredArtifact(
              required(fields, "type", index),
              required(fields, "name", index),
              optional(fields, "path", index)));
    }
    return List.copyOf(declared);
  }

  private static String required(Map<?, ?> fields, String key, int index) {
    String value = optional(fields, key, index);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(PATH + ": artifacts[" + index + "] has no " + key);
    }
    return value;
  }

  private static String optional(Map<?, ?> fields, String key, int index) {
    Object value = fields.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> || value instanceof List<?>) {
      throw new IllegalArgumentException(
          PATH + ": artifacts[" + index + "]." + key + " must be a scalar, not " + YamlDocument.kind(value));
    }
    return String.valueOf(value);
  }
}
