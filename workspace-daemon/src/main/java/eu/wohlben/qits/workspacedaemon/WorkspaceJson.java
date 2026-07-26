package eu.wohlben.qits.workspacedaemon;

import eu.wohlben.qits.workspacedaemon.detection.ComponentMap;
import eu.wohlben.qits.workspacedaemon.detection.ComponentMapEntry;
import eu.wohlben.qits.workspacedaemon.detection.ComponentSelector;
import eu.wohlben.qits.workspacedaemon.detection.Detection;
import eu.wohlben.qits.workspacedaemon.detection.DetectionProject;
import eu.wohlben.qits.workspacedaemon.detection.FileLink;
import eu.wohlben.qits.workspacedaemon.detection.FrameworkMembership;
import eu.wohlben.qits.workspacedaemon.detection.TestLink;
import eu.wohlben.qits.workspacedaemon.files.FileContent;
import eu.wohlben.qits.workspacedaemon.files.FileListing;
import eu.wohlben.qits.workspacedaemon.files.LazyDir;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Serializes the two capability modules' result records to the JSON {@link WorkspaceApi} answers
 * with. Hand-built {@code JsonObject}s for the same reason {@link ConfigJson} is: the native daemon
 * carries no Jackson, and a databind reflection registration is exactly the kind of thing that has
 * to be declared to the image builder. Those two are the module's only serializers, and they follow
 * the same shape deliberately.
 *
 * <p><b>Every key here is a wire contract, not a naming choice.</b> The host deserializes these
 * bodies straight into its existing {@code DetectionDto} / {@code ComponentMapDto} / {@code
 * WorkspaceFileContentDto} / {@code LazyDirDto} record tree with Jackson, and those DTOs are what
 * the SPA already consumes — so the whole point of the move is that the frontend contract does not
 * change. The records in {@code workspace-daemon-files} and {@code workspace-daemon-detection} were
 * ported with the DTOs' component names for that reason; this class only has to not lose them.
 * Renaming a key here silently breaks the file browser rather than failing a build.
 *
 * <p>Absent optionals are <em>omitted</em> rather than emitted as explicit nulls ({@code content}
 * on a binary file, an inline component's {@code templateFile}, an unowned link's {@code
 * projectRoot}, the unused half of a selector). Jackson maps a missing component to {@code null}
 * when it reconstructs a record, so the host sees the same value either way, and omitting keeps the
 * bodies — a component map of a large Angular repo is thousands of entries — smaller on the wire.
 * Lists are always emitted, as {@code []} when empty, because a missing list would decode to {@code
 * null} and every consumer iterates them.
 */
final class WorkspaceJson {

  private WorkspaceJson() {}

  /** {@code GET /files} — one level of the tree, plus the whole-tree generation token. */
  static JsonObject listing(FileListing listing) {
    JsonArray lazyDirs = new JsonArray();
    for (LazyDir dir : listing.lazyDirs()) {
      // No `href`: the host's LazyDirDto carries one, but it is a /api/repositories/… URL built
      // from the repository and workspace ids the daemon does not know. The host's controller
      // synthesises it from `path` on the way out, exactly as it does today.
      lazyDirs.add(new JsonObject().put("path", dir.path()).put("childCount", dir.childCount()));
    }
    return new JsonObject()
        .put("paths", new JsonArray(List.copyOf(listing.paths())))
        .put("lazyDirs", lazyDirs)
        .put("generation", listing.generation());
  }

  /** {@code GET /files/content} — one file's text, or the binary flag with no content. */
  static JsonObject content(FileContent content) {
    JsonObject json = new JsonObject().put("path", content.path()).put("binary", content.binary());
    putIfPresent(json, "content", content.content());
    return json;
  }

  /** {@code GET /detection} — projects, per-framework membership, and the source→test graph. */
  static JsonObject detection(Detection detection) {
    JsonArray projects = new JsonArray();
    for (DetectionProject project : detection.projects()) {
      projects.add(
          new JsonObject()
              .put("root", project.root())
              .put("frameworkId", project.frameworkId())
              .put("label", project.label()));
    }
    JsonArray frameworks = new JsonArray();
    for (FrameworkMembership membership : detection.frameworks()) {
      frameworks.add(
          new JsonObject()
              .put("frameworkId", membership.frameworkId())
              .put("root", membership.root())
              .put("label", membership.label())
              .put("memberPaths", new JsonArray(List.copyOf(membership.memberPaths()))));
    }
    JsonArray links = new JsonArray();
    for (FileLink link : detection.links()) {
      JsonArray tests = new JsonArray();
      for (TestLink test : link.tests()) {
        tests.add(
            new JsonObject()
                .put("path", test.path())
                .put("kinds", new JsonArray(List.copyOf(test.kinds()))));
      }
      JsonObject json = new JsonObject().put("path", link.path()).put("tests", tests);
      putIfPresent(json, "projectRoot", link.projectRoot());
      links.add(json);
    }
    return new JsonObject()
        .put("projects", projects)
        .put("frameworks", frameworks)
        .put("links", links)
        .put("generation", detection.generation());
  }

  /** {@code GET /component-map} — the components a web-view pick can be attributed to. */
  static JsonObject componentMap(ComponentMap map) {
    JsonArray components = new JsonArray();
    for (ComponentMapEntry entry : map.components()) {
      JsonArray selectors = new JsonArray();
      for (ComponentSelector selector : entry.selectors()) {
        JsonObject json = new JsonObject();
        putIfPresent(json, "element", selector.element());
        putIfPresent(json, "attribute", selector.attribute());
        selectors.add(json);
      }
      JsonObject json =
          new JsonObject()
              .put("className", entry.className())
              .put("componentFile", entry.componentFile())
              .put("styleFiles", new JsonArray(List.copyOf(entry.styleFiles())))
              .put("selectors", selectors);
      putIfPresent(json, "templateFile", entry.templateFile());
      components.add(json);
    }
    return new JsonObject().put("framework", map.framework()).put("components", components);
  }

  /**
   * The one error shape every non-2xx answer uses: {@code {"message": …}}. A single field on
   * purpose — the daemon serves an untrusted checkout to a caller that is not the end user, so the
   * body carries what the browser UI needs to render its "invalid path"/"not found" states and
   * nothing about the container's filesystem beyond the path the caller already named.
   */
  static JsonObject error(String message) {
    return new JsonObject().put("message", message);
  }

  /**
   * The body the two parent-integration routes answer with: {@code {"output": …}}, git's own text
   * from the merge. The field name matches the host DTO these replaced ({@code
   * FastForwardWorkspaceRequest.Response.output} / {@code UpdateFromParentRequest.Response.output}),
   * so the frontend contract does not move with the endpoint — the same rule the read API followed.
   */
  static JsonObject output(String output) {
    return new JsonObject().put("output", output == null ? "" : output);
  }

  private static void putIfPresent(JsonObject json, String key, Object value) {
    if (value != null) {
      json.put(key, value);
    }
  }
}
