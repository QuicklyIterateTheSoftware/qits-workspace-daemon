package eu.wohlben.qits.workspacedaemon.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.detection.AngularComponentParser.ParsedComponent;
import eu.wohlben.qits.workspacedaemon.detection.AngularComponentParser.Selector;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@code @Component} decorator extractor. The shapes mirror the {@code
 * testing-repo-quarkus-angular} fixture (inline backtick templates with control flow, flat file
 * names) plus the external-template/style variants the fixture doesn't have.
 *
 * <p>Carried over verbatim from the host's suite of the same name — the extraction is what must not
 * drift as the code changed containers.
 */
class AngularComponentParserTest {

  @Test
  void parsesInlineTemplateComponentShapedLikeTheFixture() {
    // the fixture's greeting.ts, abbreviated: inline backtick template with @if and {{ }} — the
    // braces inside it must not confuse the extraction
    String source =
        """
        import { Component, inject } from '@angular/core';

        @Component({
          selector: 'app-greeting',
          template: `
            @if (greeting(); as g) {
              <h1>Hello, {{ g.name }}!</h1>
            } @else {
              <p>Greeting…</p>
            }
          `,
        })
        export class Greeting {
          protected readonly greeting = toSignal(this.route.paramMap);
        }
        """;

    List<ParsedComponent> components =
        AngularComponentParser.parse("src/main/webui/src/app/greeting.ts", source);

    assertEquals(1, components.size());
    ParsedComponent component = components.get(0);
    assertEquals("Greeting", component.className());
    assertEquals("src/main/webui/src/app/greeting.ts", component.componentFile());
    assertNull(component.templateFile());
    assertTrue(component.styleFiles().isEmpty());
    assertEquals(List.of(new Selector("app-greeting", null)), component.selectors());
  }

  @Test
  void resolvesExternalTemplateAndStyleUrlsRelativeToTheComponentFile() {
    String source =
        """
        @Component({
          selector: 'app-detail',
          templateUrl: './detail.component.html',
          styleUrls: ['./detail.component.scss', '../shared/common.css'],
        })
        export class DetailComponent {}
        """;

    ParsedComponent component =
        AngularComponentParser.parse("src/app/detail/detail.component.ts", source).get(0);

    assertEquals("src/app/detail/detail.component.html", component.templateFile());
    assertEquals(
        List.of("src/app/detail/detail.component.scss", "src/app/shared/common.css"),
        component.styleFiles());
  }

  @Test
  void supportsSingularStyleUrl() {
    String source =
        """
        @Component({
          selector: 'app-single',
          templateUrl: 'single.html',
          styleUrl: 'single.css',
        })
        export class Single {}
        """;

    ParsedComponent component = AngularComponentParser.parse("src/app/single.ts", source).get(0);

    assertEquals("src/app/single.html", component.templateFile());
    assertEquals(List.of("src/app/single.css"), component.styleFiles());
  }

  @Test
  void dropsStyleRefsThatEscapeTheWorkspaceRoot() {
    String source =
        """
        @Component({
          selector: 'app-escape',
          templateUrl: '../../../../etc/passwd',
          styleUrls: ['/absolute.css', 'ok.css'],
        })
        export class Escape {}
        """;

    ParsedComponent component = AngularComponentParser.parse("app/escape.ts", source).get(0);

    assertNull(component.templateFile());
    assertEquals(List.of("app/ok.css"), component.styleFiles());
  }

  @Test
  void parsesMultiSelectorAlternativesIntoStructuredForm() {
    String source =
        """
        @Component({
          selector: 'app-a, [appB], button[appC], .styled, :not(form)',
          template: '',
        })
        export class Multi {}
        """;

    ParsedComponent component = AngularComponentParser.parse("multi.ts", source).get(0);

    // the class and pseudo-class alternatives are skipped, never guessed at
    assertEquals(
        List.of(
            new Selector("app-a", null),
            new Selector(null, "appB"),
            new Selector("button", "appC")),
        component.selectors());
  }

  @Test
  void attributeSelectorValueIsStrippedToItsName() {
    String source =
        """
        @Component({ selector: '[appFoo=bar]', template: '' })
        export class Valued {}
        """;

    assertEquals(
        List.of(new Selector(null, "appFoo")),
        AngularComponentParser.parse("valued.ts", source).get(0).selectors());
  }

  @Test
  void emitsSelectorlessComponentWithEmptySelectors() {
    String source =
        """
        @Component({ template: '<p>routed</p>' })
        export class RoutedOnly {}
        """;

    ParsedComponent component = AngularComponentParser.parse("routed.ts", source).get(0);

    assertEquals("RoutedOnly", component.className());
    assertTrue(component.selectors().isEmpty());
  }

  @Test
  void skipsDecoratorWithoutFollowingExportedClass() {
    String source =
        """
        // @Component({ selector: 'app-commented', template: '' })
        const note = 'no component here';
        """;

    assertTrue(AngularComponentParser.parse("note.ts", source).isEmpty());
  }

  @Test
  void parsesTwoComponentsInOneFileIndependently() {
    String source =
        """
        @Component({ selector: 'app-first', templateUrl: './first.html' })
        export class First {}

        @Component({ selector: 'app-second', template: '<span>inline</span>' })
        export class Second {}
        """;

    List<ParsedComponent> components = AngularComponentParser.parse("src/pair.ts", source);

    assertEquals(2, components.size());
    assertEquals("First", components.get(0).className());
    assertEquals("src/first.html", components.get(0).templateFile());
    assertEquals("Second", components.get(1).className());
    assertNull(components.get(1).templateFile());
    assertEquals(List.of(new Selector("app-second", null)), components.get(1).selectors());
  }

  @Test
  void uppercaseElementSelectorIsLowercasedForMatching() {
    String source =
        """
        @Component({ selector: 'APP-SHOUT', template: '' })
        export class Shout {}
        """;

    assertEquals(
        List.of(new Selector("app-shout", null)),
        AngularComponentParser.parse("shout.ts", source).get(0).selectors());
  }
}
