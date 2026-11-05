package eu.wohlben.qits.bootstrap.graph.manifest;

import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** The one way this library loads YAML: safe-constructed, into plain maps, lists and scalars. */
final class YamlDocument {

  private YamlDocument() {}

  /**
   * The document's top-level mapping; empty for a null, blank or empty document.
   *
   * @throws IllegalArgumentException when the document does not parse, or is not a mapping
   */
  static Map<?, ?> mapping(String file, String content) {
    if (content == null || content.isBlank()) {
      return Map.of();
    }
    Object document;
    try {
      document = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
    } catch (YAMLException invalid) {
      throw new IllegalArgumentException(
          file + " is not valid YAML: " + invalid.getMessage(), invalid);
    }
    if (document == null) {
      return Map.of();
    }
    if (!(document instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(
          file + " must be a mapping at the top level, not " + kind(document));
    }
    return map;
  }

  static String kind(Object value) {
    if (value instanceof java.util.List<?>) {
      return "a list";
    }
    return value instanceof Map<?, ?> ? "a mapping" : "a scalar";
  }
}
