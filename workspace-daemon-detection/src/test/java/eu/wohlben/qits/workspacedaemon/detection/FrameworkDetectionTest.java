package eu.wohlben.qits.workspacedaemon.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.detection.FrameworkDetection.DetectedProject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure framework detector — a Java port of {@code detect-frameworks.spec.ts},
 * case-for-case, so the classification stays byte-for-byte what the frontend (and then the host)
 * used to compute (nested detection, deepest-root ownership, the CamelCase-prefix test binding, the
 * fuzzy {@code [A-Z]*} folding matrix, and membership resolution).
 *
 * <p>Carried over unchanged from the host's {@code FrameworkDetectionServiceTest} apart from the
 * bean-instance receiver becoming the static class: the detector is what must not drift.
 */
class FrameworkDetectionTest {

  /** The (id, root) pairs of a detection result, sorted, for compact assertions. */
  private static List<String> shape(List<DetectedProject> projects) {
    return projects.stream().map(p -> p.descriptor().id() + "@" + p.root()).sorted().toList();
  }

  // ---- detectFrameworks ----------------------------------------------------------------------

  @Test
  void detectsNestedJavaAndAngularEachAtTheParentDirOfItsMarker() {
    List<String> paths =
        List.of(
            "pom.xml",
            "domain/pom.xml",
            "service/pom.xml",
            "service/src/main/webui/angular.json",
            "service/src/main/webui/package.json",
            "service/src/main/webui/src/app/x.ts",
            "README.md");
    assertEquals(
        List.of(
            "java-quarkus@",
            "java-quarkus@domain",
            "java-quarkus@service",
            "ts-angular@service/src/main/webui"),
        shape(FrameworkDetection.detect(paths)));
  }

  @Test
  void doesNotDetectAngularFromALonePackageJson() {
    assertEquals(
        List.of(), FrameworkDetection.detect(List.of("pkg/package.json", "pkg/src/index.ts")));
  }

  @Test
  void detectsADocsDirOnlyWhenItContainsAMarkdownAndSurfacesMultipleDocsDirs() {
    assertEquals(List.of(), shape(FrameworkDetection.detect(List.of("docs/notes.txt"))));
    assertEquals(
        List.of("docs@docs", "docs@service/docs"),
        shape(FrameworkDetection.detect(List.of("docs/plan.md", "service/docs/guide.md"))));
  }

  // ---- owningProject -------------------------------------------------------------------------

  @Test
  void picksTheDeepestRootThatPrefixesThePath() {
    List<DetectedProject> projects =
        FrameworkDetection.detect(
            List.of(
                "pom.xml",
                "service/pom.xml",
                "service/src/main/webui/angular.json",
                "docs/plan.md"));
    assertEquals(
        "ts-angular",
        FrameworkDetection.owningProject("service/src/main/webui/src/app/x.ts", projects)
            .descriptor()
            .id());
    DetectedProject javaOwner =
        FrameworkDetection.owningProject("service/src/main/java/Foo.java", projects);
    assertEquals("service", javaOwner.root());
    assertEquals("java-quarkus", javaOwner.descriptor().id());
    assertEquals("", FrameworkDetection.owningProject("pom.xml", projects).root());
    assertEquals(
        "docs", FrameworkDetection.owningProject("docs/plan.md", projects).descriptor().id());
  }

  @Test
  void returnsNullForAPathOwnedByNoProject() {
    assertNull(FrameworkDetection.owningProject("x.ts", List.of()));
  }

  // ---- memberPaths (the ported frameworkToRules + client evaluation) -------------------------

  @Test
  void resolvesAngularMembershipScopedByRoot() {
    String root = "service/src/main/webui";
    List<String> paths =
        List.of(
            root + "/package.json",
            root + "/angular.json",
            root + "/tsconfig.json",
            root + "/tsconfig.app.json",
            root + "/src/app/x.ts",
            root + "/public/favicon.ico",
            root + "/README.md", // not a member
            "pom.xml"); // not a member (outside root)
    DetectedProject angular =
        FrameworkDetection.detect(paths).stream()
            .filter(p -> p.descriptor().id().equals("ts-angular"))
            .findFirst()
            .orElseThrow();
    // membership is a set; production passes a sorted ls-files list, so compare sorted
    assertEquals(
        sorted(
            root + "/angular.json",
            root + "/package.json",
            root + "/public/favicon.ico",
            root + "/src/app/x.ts",
            root + "/tsconfig.app.json",
            root + "/tsconfig.json"),
        sorted(FrameworkDetection.memberPaths(angular, paths)));
  }

  @Test
  void resolvesJavaMembershipAtRepoRoot() {
    List<String> paths =
        List.of(
            "pom.xml",
            "src/main/java/com/App.java",
            "src/test/java/com/AppTest.java",
            "src/main/resources/application.properties",
            "src/test/resources/fixture.txt",
            "README.md"); // not a member
    DetectedProject java =
        FrameworkDetection.detect(paths).stream()
            .filter(p -> p.descriptor().id().equals("java-quarkus"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        sorted(
            "pom.xml",
            "src/main/java/com/App.java",
            "src/main/resources/application.properties",
            "src/test/java/com/AppTest.java",
            "src/test/resources/fixture.txt"),
        sorted(FrameworkDetection.memberPaths(java, paths)));
  }

  // ---- linkedTestsOf / linkedSourcesOf (the shared primitive) --------------------------------

  @Test
  void linkedTestsOfAndSourcesOfBasics() {
    List<String> paths =
        List.of(
            "pom.xml",
            "src/main/java/com/App.java",
            "src/test/java/com/AppTest.java",
            "src/test/java/com/OrphanTest.java",
            "w/angular.json",
            "w/src/foo.ts",
            "w/src/foo.spec.ts");
    List<DetectedProject> projects = FrameworkDetection.detect(paths);

    assertEquals(
        List.of("src/test/java/com/AppTest.java"),
        FrameworkDetection.linkedTestsOf("src/main/java/com/App.java", projects, paths));
    assertEquals(
        List.of("w/src/foo.spec.ts"),
        FrameworkDetection.linkedTestsOf("w/src/foo.ts", projects, paths));
    // a test is not a source of tests
    assertEquals(
        List.of(),
        FrameworkDetection.linkedTestsOf("src/test/java/com/AppTest.java", projects, paths));

    assertEquals(
        List.of("w/src/foo.ts"),
        FrameworkDetection.linkedSourcesOf("w/src/foo.spec.ts", projects, paths));
    assertEquals(
        List.of("src/main/java/com/App.java"),
        FrameworkDetection.linkedSourcesOf("src/test/java/com/AppTest.java", projects, paths));
    assertEquals(
        List.of(),
        FrameworkDetection.linkedSourcesOf("src/main/java/com/App.java", projects, paths));
    // an orphan test resolves to no existing source
    assertEquals(
        List.of(),
        FrameworkDetection.linkedSourcesOf("src/test/java/com/OrphanTest.java", projects, paths));
  }

  @Test
  void handlesQualifiedTestNamesBackToTheBaseSource() {
    List<String> p =
        List.of(
            "pom.xml",
            "src/main/java/com/TheFile.java",
            "src/test/java/com/TheFileTest.java",
            "src/test/java/com/TheFileSpecialCaseTest.java",
            "src/test/java/com/TheFileRecordingIT.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(
        sorted(
            "src/test/java/com/TheFileRecordingIT.java",
            "src/test/java/com/TheFileSpecialCaseTest.java",
            "src/test/java/com/TheFileTest.java"),
        sorted(FrameworkDetection.linkedTestsOf("src/main/java/com/TheFile.java", proj, p)));
    assertEquals(
        List.of("src/main/java/com/TheFile.java"),
        FrameworkDetection.linkedSourcesOf(
            "src/test/java/com/TheFileSpecialCaseTest.java", proj, p));
    assertEquals(
        List.of("src/main/java/com/TheFile.java"),
        FrameworkDetection.linkedSourcesOf("src/test/java/com/TheFileRecordingIT.java", proj, p));
  }

  @Test
  void attributesAQualifiedTestToTheMostSpecificSourceWhenOneExists() {
    List<String> p =
        List.of(
            "pom.xml",
            "src/main/java/com/TheFile.java",
            "src/main/java/com/TheFileSpecialCase.java",
            "src/test/java/com/TheFileTest.java",
            "src/test/java/com/TheFileSpecialCaseTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(
        List.of("src/main/java/com/TheFileSpecialCase.java"),
        FrameworkDetection.linkedSourcesOf(
            "src/test/java/com/TheFileSpecialCaseTest.java", proj, p));
    assertEquals(
        List.of("src/test/java/com/TheFileSpecialCaseTest.java"),
        FrameworkDetection.linkedTestsOf("src/main/java/com/TheFileSpecialCase.java", proj, p));
    // …so TheFile.java only owns TheFileTest, not the more-specific test
    assertEquals(
        List.of("src/test/java/com/TheFileTest.java"),
        FrameworkDetection.linkedTestsOf("src/main/java/com/TheFile.java", proj, p));
  }

  // ---- permissive java test folding (the [A-Z]* extension) -----------------------------------

  private static final String SRC = "src/main/java/com";
  private static final String TST = "src/test/java/com";

  @Test
  void foldsAScenarioNamedTestIntoTheSingleSourceThatExtendsItsPrefix() {
    List<String> p =
        List.of(
            "pom.xml",
            SRC + "/OtelProxyResource.java",
            TST + "/OtelProxyResourceTest.java",
            TST + "/OtelProxyUnreachableTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(
        List.of(SRC + "/OtelProxyResource.java"),
        FrameworkDetection.linkedSourcesOf(TST + "/OtelProxyUnreachableTest.java", proj, p));
    assertEquals(
        sorted(TST + "/OtelProxyResourceTest.java", TST + "/OtelProxyUnreachableTest.java"),
        sorted(FrameworkDetection.linkedTestsOf(SRC + "/OtelProxyResource.java", proj, p)));
  }

  @Test
  void prefersAMoreSpecificExactSourceOverAnExtensionMatch() {
    List<String> p =
        List.of(
            "pom.xml",
            SRC + "/OtelProxyResource.java",
            SRC + "/OtelProxyUnreachable.java",
            TST + "/OtelProxyUnreachableTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(
        List.of(SRC + "/OtelProxyUnreachable.java"),
        FrameworkDetection.linkedSourcesOf(TST + "/OtelProxyUnreachableTest.java", proj, p));
    assertEquals(
        List.of(), FrameworkDetection.linkedTestsOf(SRC + "/OtelProxyResource.java", proj, p));
  }

  @Test
  void foldsIntoNeitherSourceWhenTheExtensionPrefixIsAmbiguous() {
    List<String> p =
        List.of(
            "pom.xml",
            SRC + "/OtelProxyResource.java",
            SRC + "/OtelProxyClient.java",
            TST + "/OtelProxyUnreachableTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(
        List.of(),
        FrameworkDetection.linkedSourcesOf(TST + "/OtelProxyUnreachableTest.java", proj, p));
    assertEquals(
        List.of(), FrameworkDetection.linkedTestsOf(SRC + "/OtelProxyResource.java", proj, p));
    assertEquals(
        List.of(), FrameworkDetection.linkedTestsOf(SRC + "/OtelProxyClient.java", proj, p));
  }

  @Test
  void neverFuzzyFoldsATestSharingOnlyItsFirstCamelWord() {
    List<String> p = List.of("pom.xml", SRC + "/FooBaz.java", TST + "/FooBarTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(List.of(), FrameworkDetection.linkedSourcesOf(TST + "/FooBarTest.java", proj, p));
    assertEquals(List.of(), FrameworkDetection.linkedTestsOf(SRC + "/FooBaz.java", proj, p));
  }

  @Test
  void foldsAQuarkusTestLikeAnyTest() {
    List<String> p =
        List.of(
            "pom.xml", SRC + "/GreetingResource.java", TST + "/GreetingResourceQuarkusTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    assertEquals(
        List.of(SRC + "/GreetingResource.java"),
        FrameworkDetection.linkedSourcesOf(TST + "/GreetingResourceQuarkusTest.java", proj, p));
    assertEquals(
        List.of(TST + "/GreetingResourceQuarkusTest.java"),
        FrameworkDetection.linkedTestsOf(SRC + "/GreetingResource.java", proj, p));
  }

  @Test
  void openingAnyGroupMemberYieldsTheIdenticalStrip() {
    List<String> p =
        List.of(
            "pom.xml",
            SRC + "/OtelProxyResource.java",
            TST + "/OtelProxyResourceTest.java",
            TST + "/OtelProxyUnreachableTest.java");
    List<DetectedProject> proj = FrameworkDetection.detect(p);
    var fromSource =
        FrameworkDetection.resolveLinkedGroup(SRC + "/OtelProxyResource.java", proj, p);
    var fromTest =
        FrameworkDetection.resolveLinkedGroup(TST + "/OtelProxyUnreachableTest.java", proj, p);
    assertEquals(fromSource, fromTest);
    assertEquals(
        List.of(
            SRC + "/OtelProxyResource.java",
            TST + "/OtelProxyResourceTest.java",
            TST + "/OtelProxyUnreachableTest.java"),
        fromSource.stream().map(FrameworkDetection.LinkedFile::path).toList());
    assertEquals("code", fromSource.get(0).role());
  }

  @Test
  void resolveLinkedGroupReturnsEmptyForAnOrphanTestAndAnUntestedSource() {
    List<String> lonely = List.of("pom.xml", "src/main/java/com/Lonely.java");
    assertEquals(
        List.of(),
        FrameworkDetection.resolveLinkedGroup(
            "src/main/java/com/Lonely.java", FrameworkDetection.detect(lonely), lonely));
  }

  // ---- gitignore glob translator (spot checks of the ported matcher) -------------------------

  @Test
  void gitignoreGlobDistinguishesDoubleStarFromSingleStar() {
    assertTrue(
        FrameworkDetection.gitignoreGlobToRegExp("**/*.java", true)
            .matcher("a/b/C.java")
            .matches());
    assertTrue(
        FrameworkDetection.gitignoreGlobToRegExp("**/*.java", true).matcher("C.java").matches());
    assertTrue(
        FrameworkDetection.gitignoreGlobToRegExp("src/**", true).matcher("src/a/b.ts").matches());
    // single * stays within a segment
    assertTrue(
        !FrameworkDetection.gitignoreGlobToRegExp("tsconfig*.json", true)
            .matcher("tsconfig/app.json")
            .matches());
    assertTrue(
        FrameworkDetection.gitignoreGlobToRegExp("tsconfig*.json", true)
            .matcher("tsconfig.app.json")
            .matches());
  }

  // ---- ts-lit (the Vite-marker candidate rule + the .test.ts suffix pairing) -----------------

  @Test
  void detectsALitCandidateAtTheViteConfigDirButNeverInsideAnAngularWorkspace() {
    // The structural rule only: a vite.config.* marks a candidate root (the "does package.json
    // depend on lit" confirmation is DetectionService's content peek, not this class's job).
    List<String> paths =
        List.of(
            "pom.xml",
            "src/main/webui/vite.config.ts",
            "src/main/webui/package.json",
            "src/main/webui/src/components/greeting-form/greeting-form.ts");
    assertEquals(
        List.of("java-quarkus@", "ts-lit@src/main/webui"), shape(FrameworkDetection.detect(paths)));

    // The marker's other spellings count too.
    assertEquals(
        List.of("ts-lit@js", "ts-lit@mjs"),
        shape(FrameworkDetection.detect(List.of("js/vite.config.js", "mjs/vite.config.mjs"))));

    // An Angular workspace that also carries a vite config (Angular's own builder setups do) stays
    // Angular-only — the angular.json root is subtracted from the candidates.
    assertEquals(
        List.of("ts-angular@w"),
        shape(
            FrameworkDetection.detect(
                List.of("w/angular.json", "w/vite.config.ts", "w/package.json"))));
  }

  @Test
  void resolvesLitMembershipScopedByRoot() {
    String root = "src/main/webui";
    List<String> paths =
        List.of(
            root + "/package.json",
            root + "/vite.config.ts",
            root + "/vitest.config.ts",
            root + "/tsconfig.json",
            root + "/index.html",
            root + "/src/components/greeting-form/greeting-form.ts",
            root + "/public/favicon.ico",
            root + "/README.md", // not a member
            "pom.xml"); // not a member (outside root)
    DetectedProject lit =
        FrameworkDetection.detect(paths).stream()
            .filter(p -> p.descriptor().id().equals("ts-lit"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        sorted(
            root + "/index.html",
            root + "/package.json",
            root + "/public/favicon.ico",
            root + "/src/components/greeting-form/greeting-form.ts",
            root + "/tsconfig.json",
            root + "/vite.config.ts",
            root + "/vitest.config.ts"),
        sorted(FrameworkDetection.memberPaths(lit, paths)));
  }

  @Test
  void linksLitSourcesToDotTestCounterpartsBothWays() {
    String root = "src/main/webui";
    String comp = root + "/src/components/greeting-form";
    List<String> paths =
        List.of(
            root + "/vite.config.ts",
            root + "/package.json",
            comp + "/greeting-form.ts",
            comp + "/greeting-form.test.ts",
            comp + "/name-input.ts");
    List<DetectedProject> projects = FrameworkDetection.detect(paths);

    assertEquals(
        List.of(comp + "/greeting-form.test.ts"),
        FrameworkDetection.linkedTestsOf(comp + "/greeting-form.ts", projects, paths));
    assertEquals(
        List.of(comp + "/greeting-form.ts"),
        FrameworkDetection.linkedSourcesOf(comp + "/greeting-form.test.ts", projects, paths));
    // a test is not a source of tests, and an untested sub-component links nothing
    assertEquals(
        List.of(),
        FrameworkDetection.linkedTestsOf(comp + "/greeting-form.test.ts", projects, paths));
    assertEquals(
        List.of(), FrameworkDetection.linkedTestsOf(comp + "/name-input.ts", projects, paths));
  }

  @Test
  void angularSpecSuffixIsInertInsideALitRootAndViceVersa() {
    // Inside a lit root a stray .spec.ts is neither a test nor a claimable counterpart.
    List<String> litPaths = List.of("lit/vite.config.ts", "lit/src/foo.ts", "lit/src/foo.spec.ts");
    List<DetectedProject> litProjects = FrameworkDetection.detect(litPaths);
    assertEquals(
        List.of(), FrameworkDetection.linkedTestsOf("lit/src/foo.ts", litProjects, litPaths));
    assertEquals(
        List.of(),
        FrameworkDetection.linkedSourcesOf("lit/src/foo.spec.ts", litProjects, litPaths));

    // Inside an Angular root a .test.ts is a plain source, not a test.
    List<String> ngPaths = List.of("ng/angular.json", "ng/src/foo.ts", "ng/src/foo.test.ts");
    List<DetectedProject> ngProjects = FrameworkDetection.detect(ngPaths);
    assertEquals(
        List.of(), FrameworkDetection.linkedSourcesOf("ng/src/foo.test.ts", ngProjects, ngPaths));
    assertEquals(
        List.of(), FrameworkDetection.linkedTestsOf("ng/src/foo.test.ts", ngProjects, ngPaths));
  }

  private static List<String> sorted(String... values) {
    return sorted(List.of(values));
  }

  private static List<String> sorted(List<String> values) {
    List<String> copy = new ArrayList<>(values);
    copy.sort(java.util.Comparator.naturalOrder());
    return copy;
  }

  @Test
  void descriptorByIdResolvesShippedKindsAndNullOtherwise() {
    assertEquals("java-quarkus", FrameworkDetection.descriptorById("java-quarkus").id());
    assertEquals("ts-angular", FrameworkDetection.descriptorById("ts-angular").id());
    assertEquals("ts-lit", FrameworkDetection.descriptorById("ts-lit").id());
    assertEquals("docs", FrameworkDetection.descriptorById("docs").id());
    assertNull(FrameworkDetection.descriptorById("rust-cargo"));
    assertNull(FrameworkDetection.descriptorById(null));
  }
}
