package eu.wohlben.qits.bootstrap.graph.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Verbatim copies of real {@code .config/qits/} files, under {@code src/test/resources/estate/}.
 * Copied byte for byte from the wrapper's components; refresh by copying again, never by editing.
 */
final class Estate {

  private Estate() {}

  static String file(String repository, String name) {
    String resource = "/estate/" + repository + "/" + name;
    try (InputStream in = Estate.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("no test resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
