package com.krizaka.orazaka.jobservice.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.jobs.domain.port.JobExecutor;
import com.krizaka.orazaka.jobservice.catalog.RoutingKeys;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [EXEC-003] The seeded registry and the in-repo executors agree, both ways: every enabled
 * capability this process drains names a {@code handler_key} exactly one executor here returns, and
 * every executor here serves at least one enabled seeded capability.
 *
 * <p>[EXEC-001] checks the first half against the workspace; this adds what it does not: two
 * executors claiming one key (which {@code JobListener} refuses only at startup), and an executor
 * no row reaches — code that is built, tested and never dispatched, or a row whose handler key
 * drifted from the executor it was written for (orazaka-packs#138). Routing stays data: the rows
 * decide which code runs, through {@code handler_key}, never an order or a key heuristic.
 *
 * <p>An executor's {@code handlerKey()} returns a literal, so that this rule can read it; one that
 * computes it fails here and has to say why in this rule.
 */
class ExecutorRegistryFitnessTest {

  private static final Path SEED = Path.of("infra/initdb/30-jobs-config.sql");
  private static final Path WORKER = Path.of("src/main/resources/worker.yaml");
  private static final Path SOURCES = Path.of("src/main/java");

  /** A seeded row: key, handler, routing key, then (reluctantly) its enabled flag. */
  private static final Pattern ROW =
      Pattern.compile(
          "^\\('(orazaka\\.[a-z0-9.]+)', '([a-z0-9.]+)', '(job\\.[a-z0-9.*]+)',.*?,\\s*(true|false)\\)",
          Pattern.MULTILINE | Pattern.DOTALL);

  private static final Pattern BINDING = Pattern.compile("(?m)^\\s*-\\s*\"(job\\.[^\"]+)\"\\s*$");

  private static final Pattern HANDLER_KEY =
      Pattern.compile("String handlerKey\\(\\)\\s*\\{\\s*return\\s+\"([a-z0-9.]+)\";");

  /** handler key → the executor classes returning it. */
  private static final Map<String, List<String>> EXECUTORS = new TreeMap<>();

  /** handler keys of the enabled seeded capabilities this process drains. */
  private static final Set<String> SERVED = new TreeSet<>();

  @BeforeAll
  static void read() throws IOException {
    List<JavaClass> executors =
        new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.krizaka.orazaka.jobservice")
                .stream()
                .filter(type -> type.isAssignableTo(JobExecutor.class))
                .filter(type -> !type.isInterface())
                .filter(type -> !type.getModifiers().contains(JavaModifier.ABSTRACT))
                .toList();
    for (JavaClass executor : executors) {
      Path source =
          SOURCES
              .resolve(executor.getPackageName().replace('.', '/'))
              .resolve(executor.getSimpleName() + ".java");
      Matcher literal = HANDLER_KEY.matcher(Files.readString(source));
      assertThat(literal.find())
          .as("%s.handlerKey() returns a literal this rule can read", executor.getSimpleName())
          .isTrue();
      EXECUTORS.computeIfAbsent(literal.group(1), key -> new ArrayList<>()).add(executor.getName());
    }

    List<String> bindings = new ArrayList<>();
    Matcher binding = BINDING.matcher(Files.readString(WORKER));
    while (binding.find()) {
      bindings.add(binding.group(1));
    }
    Matcher row = ROW.matcher(Files.readString(SEED));
    while (row.find()) {
      String routingKey = row.group(3);
      if ("true".equals(row.group(4))
          && bindings.stream().anyMatch(drained -> RoutingKeys.matches(drained, routingKey))) {
        SERVED.add(row.group(2));
      }
    }
  }

  @Test
  @DisplayName(
      "[EXEC-003] the rule reads executors, bindings and rows — it never passes over nothing")
  void theRuleSeesItsSubjects() {
    assertThat(EXECUTORS).isNotEmpty();
    assertThat(SERVED).isNotEmpty();
  }

  @Test
  @DisplayName("[EXEC-003] one executor per handler key")
  void oneExecutorPerHandlerKey() {
    assertThat(EXECUTORS).allSatisfy((key, classes) -> assertThat(classes).as(key).hasSize(1));
  }

  @Test
  @DisplayName("[EXEC-003] every enabled capability drained here has its executor here")
  void everyServedKeyHasAnExecutor() {
    assertThat(EXECUTORS.keySet()).containsAll(SERVED);
  }

  @Test
  @DisplayName("[EXEC-003] every executor here serves an enabled seeded capability")
  void everyExecutorIsReached() {
    assertThat(SERVED).containsAll(EXECUTORS.keySet());
  }
}
