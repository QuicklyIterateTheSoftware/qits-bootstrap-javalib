package eu.wohlben.qits.bootstrap.graph.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code .config/qits/deployments.yml} — what a repository deploys as.
 *
 * <p>A flat {@code key: value} file whose values carry colons and commas ({@code resources:
 * postgresql:db, idp:client}); none of them is followed by a space, so YAML reads each value as one
 * plain string. Only {@code application:} and {@code resources:} are read here; the routing, host,
 * health and navigation keys are the deployer's.
 */
public final class DeploymentDeclaration {

  /** Where the file is, repository-relative. */
  public static final String PATH = ".config/qits/deployments.yml";

  private DeploymentDeclaration() {}

  /**
   * The application and its resources; both empty for an empty file.
   *
   * @throws IllegalArgumentException when the file is not a YAML mapping, or either key holds a list
   *     or a mapping rather than a string
   */
  public static Deployment parse(String deploymentsYml) {
    Map<?, ?> fields = YamlDocument.mapping(PATH, deploymentsYml);
    String application = scalar(fields, "application");
    String resources = scalar(fields, "resources");
    List<String> entries = new ArrayList<>();
    if (resources != null) {
      for (String entry : resources.split(",")) {
        String trimmed = entry.trim();
        if (!trimmed.isEmpty()) {
          entries.add(trimmed);
        }
      }
    }
    return new Deployment(
        application == null || application.isBlank() ? null : application.trim(), entries);
  }

  private static String scalar(Map<?, ?> fields, String key) {
    Object value = fields.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Map<?, ?> || value instanceof List<?>) {
      throw new IllegalArgumentException(
          PATH + ": " + key + " must be a string, not " + YamlDocument.kind(value));
    }
    return String.valueOf(value);
  }
}
