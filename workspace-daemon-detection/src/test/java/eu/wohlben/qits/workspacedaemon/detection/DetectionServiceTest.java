package eu.wohlben.qits.workspacedaemon.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator over a fake checkout: the content peeks the pure detector cannot do (a pom's
 * Quarkus label, a Vite root's lit confirmation, a TS project's runner), the declared-framework
 * merge, and the marker-keyed cache. The end-to-end expectations mirror the host's {@code
 * WorkspaceControllerTest} detection cases, so the JSON the browser sees is unchanged by the move
 * into the container.
 */
class DetectionServiceTest {

  private static DetectionProject project(Detection detection, String root) {
    return detection.projects().stream()
        .filter(p -> p.root().equals(root))
        .findFirst()
        .orElse(null);
  }

  private static FrameworkMembership membership(Detection detection, String frameworkId) {
    return detection.frameworks().stream()
        .filter(f -> f.frameworkId().equals(frameworkId))
        .findFirst()
        .orElseThrow();
  }

  private static FileLink link(Detection detection, String path) {
    return detection.links().stream().filter(l -> l.path().equals(path)).findFirst().orElseThrow();
  }

  /**
   * The whole feature in one scan: a pom-refined Java root nested with a Vitest Angular project.
   */
  @Test
  void returnsProjectsMembershipAndLinksInOneScan() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file(
                "pom.xml",
                "<project><dependency><groupId>io.quarkus</groupId></dependency></project>")
            .file("src/main/java/com/App.java", "package com; class App {}")
            .file("src/test/java/com/AppTest.java", "package com; class AppTest {}")
            .file(
                "web/angular.json",
                "{ \"projects\": { \"app\": { \"architect\": { \"test\": { \"builder\":"
                    + " \"@angular/build:unit-test\" } } } } }")
            .file("web/src/foo.ts", "export const foo = 1;")
            .file("web/src/foo.spec.ts", "describe('foo', () => {});");

    Detection detection = new DetectionService(files).detect("marker-1");

    // projects: the Java root pom-refined to Quarkus, plus the nested Angular root
    assertEquals("java-quarkus", project(detection, "").frameworkId());
    assertEquals("Java / Quarkus", project(detection, "").label());
    assertEquals("ts-angular", project(detection, "web").frameworkId());

    // membership: all detected frameworks' member sets, in the one result
    assertTrue(
        membership(detection, "java-quarkus")
            .memberPaths()
            .containsAll(
                List.of(
                    "pom.xml", "src/main/java/com/App.java", "src/test/java/com/AppTest.java")));
    assertTrue(
        membership(detection, "ts-angular")
            .memberPaths()
            .containsAll(List.of("web/angular.json", "web/src/foo.ts", "web/src/foo.spec.ts")));

    // links: Java source → its JUnit test, Angular source → its config-detected Vitest spec
    FileLink java = link(detection, "src/main/java/com/App.java");
    assertEquals("", java.projectRoot());
    assertEquals(
        List.of(new TestLink("src/test/java/com/AppTest.java", List.of("junit"))), java.tests());
    FileLink angular = link(detection, "web/src/foo.ts");
    assertEquals("web", angular.projectRoot());
    assertEquals(List.of(new TestLink("web/src/foo.spec.ts", List.of("vitest"))), angular.tests());
  }

  /**
   * The Lit stack: a Vite root confirmed by the {@code lit} dependency peek is a project, one
   * without it is no project at all (never mislabeled), and the {@code *.test.ts} pairing links
   * with the config-detected Vitest runner.
   */
  @Test
  void recognisesALitProjectViaContentPeekAndLinksDotTests() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("lit/vite.config.ts", "export default {};")
            .file("lit/vitest.config.ts", "export default {};")
            .file("lit/package.json", "{ \"dependencies\": { \"lit\": \"^3.0.0\" } }")
            .file("lit/src/foo.ts", "export const foo = 1;")
            .file("lit/src/foo.test.ts", "test('foo', () => {});")
            // a Vite project with no lit dependency (a React/Vue shape) — must NOT be classified
            .file("other/vite.config.ts", "export default {};")
            .file("other/package.json", "{ \"dependencies\": { \"react\": \"^19.0.0\" } }")
            .file("other/src/app.ts", "export const app = 1;");

    Detection detection = new DetectionService(files).detect("marker-1");

    assertEquals("ts-lit", project(detection, "lit").frameworkId());
    assertEquals("TypeScript / Lit", project(detection, "lit").label());
    assertNull(project(detection, "other"));
    assertTrue(
        membership(detection, "ts-lit")
            .memberPaths()
            .containsAll(List.of("lit/vite.config.ts", "lit/package.json", "lit/src/foo.test.ts")));
    FileLink lit = link(detection, "lit/src/foo.ts");
    assertEquals("lit", lit.projectRoot());
    assertEquals(List.of(new TestLink("lit/src/foo.test.ts", List.of("vitest"))), lit.tests());
  }

  @Test
  void isEmptyForATreeWithNoRecognisedFramework() {
    Detection detection =
        new DetectionService(new FakeWorkspaceFiles().file("README.txt", "hi")).detect("m");

    assertEquals(List.of(), detection.projects());
    assertEquals(List.of(), detection.frameworks());
    assertEquals(List.of(), detection.links());
    assertNotNull(detection.generation());
  }

  @Test
  void javaLabelFallsBackToMavenWhenThePomDoesNotMentionQuarkus() {
    Detection detection =
        new DetectionService(new FakeWorkspaceFiles().file("pom.xml", "<project/>")).detect("m");

    assertEquals("Java / Maven", project(detection, "").label());
  }

  /** The runner is config-detected per root, never guessed; nothing recognised stays open-ended. */
  @Test
  void detectsRunnerFromAngularBuilderThenConfigFilesAndOtherwiseUnspecified() {
    FakeWorkspaceFiles karma =
        new FakeWorkspaceFiles()
            .file("angular.json", "{ \"builder\": \"@angular-devkit/build-angular:karma\" }")
            .file("src/a.ts")
            .file("src/a.spec.ts");
    assertEquals(
        List.of("karma-jasmine"),
        link(new DetectionService(karma).detect("m"), "src/a.ts").tests().get(0).kinds());

    FakeWorkspaceFiles playwright =
        new FakeWorkspaceFiles()
            .file("angular.json", "{}")
            .file("playwright.config.mts", "export default {};")
            .file("src/a.ts")
            .file("src/a.spec.ts");
    assertEquals(
        List.of("playwright"),
        link(new DetectionService(playwright).detect("m"), "src/a.ts").tests().get(0).kinds());

    FakeWorkspaceFiles bare =
        new FakeWorkspaceFiles().file("angular.json", "{}").file("src/a.ts").file("src/a.spec.ts");
    assertEquals(
        List.of("unspecified"),
        link(new DetectionService(bare).detect("m"), "src/a.ts").tests().get(0).kinds());
  }

  // ---- declared frameworks (the config-sourced hints) ----------------------------------------

  @Test
  void declaredFrameworksAddRootsMarkersMissAndAreDedupedAgainstDetectedOnes() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("web/angular.json", "{}")
            // a Vite root the lit peek rejects — a declared entry must still surface it
            .file("other/vite.config.ts", "export default {};")
            .file("other/package.json", "{ \"dependencies\": { \"react\": \"^19.0.0\" } }");

    Detection detection =
        new DetectionService(
                files,
                () ->
                    List.of(
                        new DeclaredFramework("ts-lit", "other"),
                        new DeclaredFramework("ts-angular", "web"), // same key as the detected one
                        new DeclaredFramework("java-quarkus", "/"), // normalized to the tree root
                        new DeclaredFramework("rust-cargo", "rs"))) // unknown kind, ignored
            .detect("m");

    assertEquals(
        List.of("ts-lit@other", "ts-angular@web", "java-quarkus@"),
        detection.projects().stream().map(p -> p.frameworkId() + "@" + p.root()).toList());
  }

  @Test
  void aFailingDeclaredFrameworkReadFallsBackToMarkerDetection() {
    FakeWorkspaceFiles files = new FakeWorkspaceFiles().file("web/angular.json", "{}");

    Detection detection =
        new DetectionService(
                files,
                () -> {
                  throw new IllegalStateException("unparseable config");
                })
            .detect("m");

    assertEquals(
        List.of("ts-angular"),
        detection.projects().stream().map(DetectionProject::frameworkId).toList());
  }

  // ---- the caller-supplied marker cache -------------------------------------------------------

  @Test
  void servesFromCacheWhileTheMarkerHoldsAndRescansWhenItMoves() {
    FakeWorkspaceFiles files = new FakeWorkspaceFiles().file("web/angular.json", "{}");
    DetectionService service = new DetectionService(files);

    Detection first = service.detect("marker-1");
    // a tree change the caller's marker has not yet reflected must NOT be picked up
    files.file("pom.xml", "<project/>");
    assertSame(first, service.detect("marker-1"));

    // …and must be, the moment the marker moves
    assertEquals("java-quarkus", project(service.detect("marker-2"), "").frameworkId());
  }

  @Test
  void invalidateForcesARescanUnderTheSameMarker() {
    FakeWorkspaceFiles files = new FakeWorkspaceFiles().file("web/angular.json", "{}");
    DetectionService service = new DetectionService(files);

    service.detect("marker-1");
    files.file("pom.xml", "<project/>");
    service.invalidate();

    assertEquals("java-quarkus", project(service.detect("marker-1"), "").frameworkId());
  }

  // ---- normalization / containment ------------------------------------------------------------

  @Test
  void generationTokenIsTheHashOfTheNormalizedPathListRegardlessOfListingOrder() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("web/angular.json", "{}")
            .file("pom.xml", "<project/>")
            .duplicateInListing("pom.xml");

    Detection detection = new DetectionService(files).detect("m");

    assertEquals(TreeGeneration.of(List.of("pom.xml", "web/angular.json")), detection.generation());
  }

  /**
   * A content peek through a symlinked directory that leaves the checkout is refused, so the label
   * degrades rather than the daemon reading an arbitrary host file.
   */
  @Test
  void doesNotPeekThroughAPathThatResolvesOutsideTheWorkspaceRoot() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("escaped/pom.xml", "<project><groupId>io.quarkus</groupId></project>")
            .escaping("escaped");

    Detection detection = new DetectionService(files).detect("m");

    assertEquals("Java / Maven", project(detection, "escaped").label());
  }
}
