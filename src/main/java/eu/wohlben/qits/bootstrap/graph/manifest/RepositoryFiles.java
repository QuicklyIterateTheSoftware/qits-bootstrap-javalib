package eu.wohlben.qits.bootstrap.graph.manifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One repository's files at ONE revision, as the caller sourced them.
 *
 * <p><b>The library never sources a fact.</b> qits-maintenance-service answers this from the git
 * host's tree and blob routes, qits-bootstrap-cli from a local clone; both choose the revision, and
 * every method here answers at that same revision, so the pins read from it correspond to a commit
 * that existed.
 *
 * <p><b>Failure is the caller's to raise.</b> A read that could not be made — a timeout, a refused
 * blob — is not an absence: throw an unchecked exception of your own from {@link #read} and it
 * propagates out of {@link ManifestReader#read} untouched, so no partial set of pins is ever
 * returned for a revision whose reading failed.
 */
public interface RepositoryFiles {

  /**
   * The content of the file at {@code path}, repository-relative, at the revision the caller chose;
   * empty when the revision holds no file there.
   */
  Optional<String> read(String path);

  /** Every tracked file path, repository-relative. Gitlinks and directories are not files. */
  List<String> paths();

  /**
   * Every gitlink: submodule path, repository-relative, to the commit sha its tree entry records
   * (the mode {@code 160000} entries). A path whose sha the caller cannot report is left out.
   */
  Map<String, String> gitlinks();
}
