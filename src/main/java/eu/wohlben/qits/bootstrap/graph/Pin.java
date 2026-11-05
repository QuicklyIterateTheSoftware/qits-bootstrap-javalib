package eu.wohlben.qits.bootstrap.graph;

import java.util.Objects;

/**
 * One coordinate a repository consumes, and where.
 *
 * @param coordinate what is pinned
 * @param version the version (or, for a gitlink, the sha) pinned now; may be null
 * @param manifestPath the file it is set in, repository-relative; may be null
 * @param location where in that file, as the parsers spell it; may be null
 */
public record Pin(Coordinate coordinate, String version, String manifestPath, String location) {

  public Pin {
    Objects.requireNonNull(coordinate, "a pin needs a coordinate");
  }
}
