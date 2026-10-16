package com.krizaka.orazaka.jobservice.architecture;

import com.krizaka.test.architecture.ArchitectureRules;
import com.krizaka.test.architecture.FrozenRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structure of {@code .}: the shared {@link ArchitectureRules}, frozen. The violations that
 * exist today are recorded in {@code archunit_store/} (committed); a new one fails the build and
 * the debt can only shrink. To fix one, fix the code and commit the smaller store.
 */
class JobServiceStructureTest {

  private static final String PKG = "com.krizaka.orazaka.jobservice";
  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    // this module's own classes: its siblings are on the classpath, their debt is frozen in their
    // own store
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> location.contains("."))
            .importPackages(PKG);
  }

  @Test
  @DisplayName("No cycle between the packages of the module")
  void noPackageCycles() {
    FrozenRules.check(ArchitectureRules.noPackageCyclesRule(PKG), classes);
  }

  @Test
  @DisplayName("Controllers are named *Controller")
  void controllersNamed() {
    FrozenRules.check(ArchitectureRules.controllersNamedControllerRule(), classes);
  }

  @Test
  @DisplayName("Services are named *Service")
  void servicesNamed() {
    FrozenRules.check(ArchitectureRules.servicesNamedServiceRule(), classes);
  }

  @Test
  @DisplayName("The environment is read in configuration only")
  void environmentOnlyInConfig() {
    FrozenRules.check(ArchitectureRules.noEnvironmentOutsideConfigRule("..config.."), classes);
  }

  @Test
  @DisplayName("A transaction starts on a service only")
  void transactionalOnlyOnServices() {
    FrozenRules.check(
        ArchitectureRules.transactionalOnlyOnServicesRule(
            "..application.service..", "..application.."),
        classes);
  }
}
