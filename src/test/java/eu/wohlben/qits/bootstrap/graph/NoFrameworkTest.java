package eu.wohlben.qits.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The library stays plain Java: qits-bootstrap-cli links it into a GraalVM native image and
 * qits-maintenance-service into Quarkus, and neither may inherit a framework, a database or
 * reflection from it. Read as text, so a fully qualified use is caught as well as an import.
 */
class NoFrameworkTest {

  private static final List<String> FORBIDDEN =
      List.of(
          "jakarta.", "javax.inject", "java.sql", "io.quarkus", "org.jboss", "java.lang.reflect",
          "lombok");

  @Test
  void noMainSourceReachesForAFramework() throws IOException {
    Path sources = Path.of("src", "main", "java");
    List<String> offences = new ArrayList<>();
    int files = 0;
    try (Stream<Path> walk = Files.walk(sources)) {
      for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
        files++;
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
          for (String forbidden : FORBIDDEN) {
            if (lines.get(index).contains(forbidden)) {
              offences.add(file + ":" + (index + 1) + " uses " + forbidden);
            }
          }
        }
      }
    }
    assertTrue(files > 0, "no sources found under " + sources.toAbsolutePath());
    assertEquals(List.of(), offences);
  }
}
