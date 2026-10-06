package com.orazaka.jobservice.infrastructure.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Helper to resolve paths relative to the monorepo root directory, preventing the creation of
 * duplicate "var" folders inside router submodule directories.
 */
public final class PathResolver {

  private PathResolver() {}

  /**
   * Resolves the path relative to the monorepo root directory. Finds the root directory by
   * traversing upwards until a directory containing "AGENTS.md" is found.
   *
   * @param path target relative path
   * @return absolute, normalized Path matching root directory scope
   */
  public static Path resolve(String path) {
    Path current = Paths.get(".").toAbsolutePath().normalize();
    Path target = Paths.get(path);
    if (!target.isAbsolute()) {
      Path root = current;
      while (root != null && !Files.exists(root.resolve("AGENTS.md"))) {
        root = root.getParent();
      }
      if (root == null) {
        root = current;
      }
      target = root.resolve(path).normalize();
    }
    return target.toAbsolutePath().normalize();
  }

  /**
   * Resolves the path relative to the monorepo root directory and returns its string
   * representation.
   *
   * @param path target relative path
   * @return absolute, normalized path string representation
   */
  public static String resolveToString(String path) {
    return resolve(path).toString();
  }
}
