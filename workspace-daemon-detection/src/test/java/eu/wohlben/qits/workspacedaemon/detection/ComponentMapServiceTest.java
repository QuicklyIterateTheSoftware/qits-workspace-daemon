package eu.wohlben.qits.workspacedaemon.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The component-map orchestrator over a fake checkout: candidate selection ({@code git grep} over
 * tracked <em>and</em> untracked {@code .ts}, specs excluded), the empty-map-over-error contract,
 * the containment guard, and the marker-keyed cache. Mirrors the host's {@code
 * WorkspaceControllerTest} component-map cases.
 */
class ComponentMapServiceTest {

  private static final String INLINE_COMPONENT =
      """
      import { Component } from '@angular/core';

      @Component({
        selector: 'app-greeting',
        template: `<h1>Hello, {{ name() }}!</h1>`,
      })
      export class Greeting {}
      """;

  private static ComponentMapEntry entry(ComponentMap map, String className) {
    return map.components().stream()
        .filter(c -> c.className().equals(className))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void scansInlineAndExternalTemplateComponents() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("src/app/greeting.ts", INLINE_COMPONENT)
            .file(
                "src/app/detail/detail.ts",
                """
                @Component({
                  selector: 'app-detail, [appDetail]',
                  templateUrl: './detail.html',
                  styleUrls: ['./detail.scss'],
                })
                export class Detail {}
                """);

    ComponentMap map = new ComponentMapService(files).componentMap("m");

    assertEquals("angular", map.framework());
    assertEquals(2, map.components().size());
    // the inline-template component carries only its .ts file
    ComponentMapEntry greeting = entry(map, "Greeting");
    assertEquals("src/app/greeting.ts", greeting.componentFile());
    assertNull(greeting.templateFile());
    assertTrue(greeting.styleFiles().isEmpty());
    assertEquals(List.of(new ComponentSelector("app-greeting", null)), greeting.selectors());
    // external refs resolve relative to the component file; the multi-selector is structured
    ComponentMapEntry detail = entry(map, "Detail");
    assertEquals("src/app/detail/detail.html", detail.templateFile());
    assertEquals(List.of("src/app/detail/detail.scss"), detail.styleFiles());
    assertEquals(
        List.of(
            new ComponentSelector("app-detail", null), new ComponentSelector(null, "appDetail")),
        detail.selectors());
  }

  /** {@code git grep} exits 1 when nothing matches — that must be an empty map, never an error. */
  @Test
  void isEmptyForATreeWithoutComponents() {
    ComponentMap map =
        new ComponentMapService(new FakeWorkspaceFiles().file("README.md", "no typescript here"))
            .componentMap("m");

    assertEquals("angular", map.framework());
    assertEquals(List.of(), map.components());
  }

  @Test
  void excludesSpecFilesSoTestHostComponentsNeverPolluteTheMap() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("greeting.spec.ts", INLINE_COMPONENT.replace("Greeting", "TestHost"));

    assertEquals(List.of(), new ComponentMapService(files).componentMap("m").components());
  }

  /**
   * A symlink's blob is its target text, so {@code git grep} can list one whose target string
   * contains {@code @Component}; reading it would dereference out of the checkout.
   */
  @Test
  void skipsCandidatesThatResolveOutsideTheWorkspaceRoot() {
    FakeWorkspaceFiles files =
        new FakeWorkspaceFiles()
            .file("linked/greeting.ts", INLINE_COMPONENT)
            .escaping("linked")
            .file("real/greeting.ts", INLINE_COMPONENT);

    ComponentMap map = new ComponentMapService(files).componentMap("m");

    assertEquals(
        List.of("real/greeting.ts"),
        map.components().stream().map(ComponentMapEntry::componentFile).toList());
  }

  @Test
  void servesFromCacheWhileTheMarkerHoldsAndRescansWhenItMoves() {
    FakeWorkspaceFiles files = new FakeWorkspaceFiles().file("a.ts", INLINE_COMPONENT);
    ComponentMapService service = new ComponentMapService(files);

    ComponentMap first = service.componentMap("marker-1");
    files.file("b.ts", INLINE_COMPONENT.replace("Greeting", "Second"));
    assertSame(first, service.componentMap("marker-1"));

    assertEquals(2, service.componentMap("marker-2").components().size());
  }

  @Test
  void invalidateForcesARescanUnderTheSameMarker() {
    FakeWorkspaceFiles files = new FakeWorkspaceFiles().file("a.ts", INLINE_COMPONENT);
    ComponentMapService service = new ComponentMapService(files);

    service.componentMap("marker-1");
    files.file("b.ts", INLINE_COMPONENT.replace("Greeting", "Second"));
    service.invalidate();

    assertEquals(2, service.componentMap("marker-1").components().size());
  }
}
