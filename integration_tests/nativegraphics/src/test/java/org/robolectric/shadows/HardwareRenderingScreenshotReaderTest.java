package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.ColorDrawable;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.ViewRootImpl;
import android.widget.FrameLayout;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.junit.rules.SetSystemPropertyRule;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.HardwareScreenshotReaderSession.Lease;
import org.robolectric.util.ReflectionHelpers;

/** Pixel fidelity, producer detachment and reset integration for native reader reuse. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HardwareRenderingScreenshotReaderTest {
  private static final int WIDTH = 48;
  private static final int HEIGHT = 32;
  private static final long PIXEL_LIMIT = 4096;

  @Rule public SetSystemPropertyRule properties = new SetSystemPropertyRule();

  @Before
  public void enableReaderReuse() {
    properties.set(HardwareRenderingScreenshot.PIXEL_COPY_RENDER_MODE, "hardware");
    properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "true");
    properties.set(HardwareRenderingScreenshot.MAX_RETAINED_READER_PIXELS, "4096");
    properties.set("robolectric.useRealDrawTraversals", "false");
    ShadowView.setUseRealDrawTraversals(false);
    HardwareRenderingScreenshot.clearReaderSession();
  }

  @After
  public void clearReaders() {
    HardwareRenderingScreenshot.clearReaderSession();
  }

  @Test
  public void repeatedFrames_reuseNativeTargetClosePlanesAndPreserveRenderer() {
    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View view = content(activity.get());
      ImageReader reader = retainReader();
      Surface surface = reader.getSurface();
      try (ControlledRenderer renderer = new ControlledRenderer(view)) {
        for (int color : new int[] {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW}) {
          view.setBackgroundColor(color);
          AtomicReference<Plane> planeReference = new AtomicReference<>();
          Bitmap actual = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
          try {
            HardwareRenderingScreenshot.capturePlane(
                view,
                plane -> {
                  planeReference.set(plane);
                  actual.copyPixelsFromBuffer(plane.getBuffer());
                  return null;
                });

            assertThat(renderer.lastTarget).isSameInstanceAs(surface);
            assertThat(renderer.currentTarget).isNull();
            assertThat(renderer.destroyCount).isEqualTo(0);
            assertThat(surface.isValid()).isTrue();
            assertThat(actual.getPixel(0, 0)).isEqualTo(color);
            assertThat(actual.getPixel(WIDTH - 1, HEIGHT - 1)).isEqualTo(color);
            assertThrows(IllegalStateException.class, () -> planeReference.get().getBuffer());
            assertMatchesFresh(view, actual);
          } finally {
            actual.recycle();
          }
          assertThat(retainReader()).isSameInstanceAs(reader);
        }
      }
    }
  }

  @Test
  public void transparentAndRemovedContent_matchesFreshPixelsAfterPartialInvalidation() {
    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      FrameLayout view = content(activity.get());
      ShadowViewRootImpl shadowRoot = Shadow.extract(viewRoot(view));
      Object embeddedRenderer = shadowRoot.getThreadedRenderer();
      ((HardwareRenderer) embeddedRenderer).setOpaque(false);
      View child = new View(activity.get());
      child.setBackgroundColor(Color.RED);
      view.addView(child, new FrameLayout.LayoutParams(WIDTH / 2, HEIGHT / 2));
      child.layout(0, 0, WIDTH / 2, HEIGHT / 2);
      view.setBackgroundColor(Color.BLUE);
      assertMatchesFresh(view);

      view.setBackgroundColor(Color.TRANSPARENT);
      Bitmap transparent = capture(view);
      try {
        assertThat(transparent.getPixel(0, 0)).isEqualTo(Color.RED);
        assertThat(transparent.getPixel(WIDTH - 1, HEIGHT - 1)).isEqualTo(Color.TRANSPARENT);
        assertMatchesFresh(view, transparent);
      } finally {
        transparent.recycle();
      }
      child.setBackgroundColor(Color.GREEN);
      child.invalidate(new Rect(0, 0, 3, 3));
      assertMatchesFresh(view);
      view.removeView(child);
      assertMatchesFresh(view);
      view.setBackgroundColor(0x7f19a3e7);
      assertMatchesFresh(view);

      assertThat(shadowRoot.getThreadedRenderer()).isSameInstanceAs(embeddedRenderer);
      assertThat(rendererMap().get(viewRoot(view))).isSameInstanceAs(embeddedRenderer);
    }
  }

  @Test
  public void alternatingActivityAndDialogRoots_reconnectSameReaderWithFreshPixels() {
    try (var first = Robolectric.buildActivity(Activity.class).setup().visible();
        var second = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View firstView = content(first.get());
      View secondView = content(second.get());
      firstView.setBackgroundColor(Color.RED);
      secondView.setBackgroundColor(Color.BLUE);
      Dialog dialog = new Dialog(first.get());
      dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      dialog.show();
      try {
        // Dialog attachment is posted to the main looper; complete it before capturing its root.
        shadowOf(Looper.getMainLooper()).idle();
        firstView.layout(0, 0, WIDTH, HEIGHT);
        secondView.layout(0, 0, WIDTH, HEIGHT);
        View dialogView = dialog.getWindow().getDecorView();
        dialogView.layout(0, 0, WIDTH, HEIGHT);
        dialogView.setBackgroundColor(0x7f00ff00);
        ImageReader reader = retainReader();
        for (View view :
            new View[] {firstView, secondView, dialogView, firstView, dialogView, secondView}) {
          assertMatchesFresh(view);
          assertThat(retainReader()).isSameInstanceAs(reader);
        }
      } finally {
        dialog.dismiss();
      }
    }
  }

  @Test
  public void nestedCapture_usesPrivateTargetWhileOuterPixelsRemainReadable() {
    try (var first = Robolectric.buildActivity(Activity.class).setup().visible();
        var second = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View outer = content(first.get());
      View inner = content(second.get());
      outer.setBackgroundColor(Color.RED);
      inner.setBackgroundColor(Color.BLUE);
      ImageReader reader = retainReader();
      try (ControlledRenderer outerRenderer = new ControlledRenderer(outer);
          ControlledRenderer innerRenderer = new ControlledRenderer(inner)) {
        Bitmap before = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Bitmap after = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try {
          HardwareRenderingScreenshot.capturePlane(
              outer,
              plane -> {
                before.copyPixelsFromBuffer(plane.getBuffer().duplicate());
                Bitmap innerBitmap = capture(inner);
                try {
                  assertThat(innerBitmap.getPixel(0, 0)).isEqualTo(Color.BLUE);
                } finally {
                  innerBitmap.recycle();
                }
                assertThat(innerRenderer.lastTarget).isNotSameInstanceAs(reader.getSurface());
                assertThat(innerRenderer.lastTarget.isValid()).isFalse();
                assertThat(reader.getSurface().isValid()).isTrue();
                after.copyPixelsFromBuffer(plane.getBuffer().duplicate());
                return null;
              });

          assertThat(before.getPixel(0, 0)).isEqualTo(Color.RED);
          assertThat(after.sameAs(before)).isTrue();
          assertThat(outerRenderer.currentTarget).isNull();
          assertThat(retainReader()).isSameInstanceAs(reader);
        } finally {
          before.recycle();
          after.recycle();
        }
      }
    }
  }

  @Test
  public void consumerAndDetachFailures_preservePrimaryExceptionAndDiscardReader() {
    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View view = content(activity.get());
      view.setBackgroundColor(Color.RED);
      Surface failedSurface = retainReader().getSurface();
      RuntimeException consumerFailure = new RuntimeException("consumer failed");
      RuntimeException detachFailure = new RuntimeException("detach failed");
      AtomicReference<Plane> planeReference = new AtomicReference<>();
      try (ControlledRenderer renderer = new ControlledRenderer(view)) {
        renderer.detachFailure = detachFailure;
        RuntimeException actual =
            assertThrows(
                RuntimeException.class,
                () ->
                    HardwareRenderingScreenshot.capturePlane(
                        view,
                        plane -> {
                          planeReference.set(plane);
                          throw consumerFailure;
                        }));

        assertThat(actual).isSameInstanceAs(consumerFailure);
        assertThat(actual.getSuppressed()).asList().containsExactly(detachFailure);
        assertThrows(IllegalStateException.class, () -> planeReference.get().getBuffer());
        assertThat(failedSurface.isValid()).isFalse();
        renderer.detachFailure = null;
        assertMatchesFresh(view);
        assertThat(retainReader().getSurface()).isNotSameInstanceAs(failedSurface);
      }
    }
  }

  @Test
  public void renderOrDetachFailure_discardsReaderAndAllowsNextCapture() {
    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View view = content(activity.get());
      view.setBackgroundColor(Color.GREEN);
      try (ControlledRenderer renderer = new ControlledRenderer(view)) {
        for (boolean failDuringRender : new boolean[] {true, false}) {
          Surface failedSurface = retainReader().getSurface();
          RuntimeException failure = new RuntimeException("capture failed");
          if (failDuringRender) {
            renderer.renderFailure = failure;
          } else {
            renderer.detachFailure = failure;
          }

          assertThat(
                  assertThrows(
                      RuntimeException.class,
                      () -> HardwareRenderingScreenshot.capturePlane(view, plane -> null)))
              .isSameInstanceAs(failure);
          assertThat(failedSurface.isValid()).isFalse();
          renderer.renderFailure = null;
          renderer.detachFailure = null;
          assertMatchesFresh(view);
        }
      }
    }
  }

  @Test
  public void registeredResetHook_closesIdleReaderAndInvalidatesActiveLease() {
    Surface idleSurface = retainReader().getSurface();
    properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "false");
    ShadowViewRootImpl.reset();
    assertThat(idleSurface.isValid()).isFalse();
    properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "true");

    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View view = content(activity.get());
      Surface activeSurface = retainReader().getSurface();
      AtomicReference<ImageReader> replacement = new AtomicReference<>();
      HardwareRenderingScreenshot.capturePlane(
          view,
          plane -> {
            ShadowViewRootImpl.reset();
            assertThat(activeSurface.isValid()).isTrue();
            assertThat(plane.getBuffer()).isNotNull();
            replacement.set(retainReader());
            return null;
          });

      assertThat(activeSurface.isValid()).isFalse();
      assertThat(retainReader()).isSameInstanceAs(replacement.get());
      assertMatchesFresh(view);
    }
  }

  @Test
  public void disabledProperty_usesFreshReaderAndPreservesIdleEntry() {
    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View view = content(activity.get());
      ImageReader idle = retainReader();
      properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "false");
      try (ControlledRenderer renderer = new ControlledRenderer(view)) {
        Bitmap bitmap = capture(view);
        bitmap.recycle();

        assertThat(renderer.lastTarget).isNotSameInstanceAs(idle.getSurface());
        assertThat(renderer.lastTarget.isValid()).isFalse();
        assertThat(idle.getSurface().isValid()).isTrue();
        assertThat(retainReader()).isSameInstanceAs(idle);
      }
    }
  }

  @Test
  public void capacityTwo_alternatingDimensionsReuseTargetsAndMatchFreshNativePixels() {
    properties.set(HardwareRenderingScreenshot.READER_CAPACITY, "2");
    properties.set(HardwareRenderingScreenshot.MAX_RETAINED_READER_BYTES, "16384");
    try (var first = Robolectric.buildActivity(Activity.class).setup().visible();
        var second = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View firstView = content(first.get());
      View secondView = content(second.get());
      secondView.layout(0, 0, HEIGHT, WIDTH);
      Surface firstTarget = retainReader(WIDTH, HEIGHT, 2, 16384).getSurface();
      Surface secondTarget = retainReader(HEIGHT, WIDTH, 2, 16384).getSurface();
      try (ControlledRenderer firstRenderer = new ControlledRenderer(firstView);
          ControlledRenderer secondRenderer = new ControlledRenderer(secondView)) {
        for (int color : new int[] {Color.RED, Color.GREEN, Color.BLUE}) {
          firstView.setBackgroundColor(color);
          secondView.setBackgroundColor(color ^ 0x00ffffff);
          for (View view : new View[] {firstView, secondView}) {
            Bitmap bitmap = capture(view);
            try {
              ControlledRenderer renderer = view == firstView ? firstRenderer : secondRenderer;
              assertThat(renderer.lastTarget)
                  .isSameInstanceAs(view == firstView ? firstTarget : secondTarget);
              assertThat(renderer.currentTarget).isNull();
              assertMatchesFresh(view, bitmap);
            } finally {
              bitmap.recycle();
            }
          }
        }
      }
      properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "false");
      ShadowViewRootImpl.reset();
      assertThat(firstTarget.isValid()).isFalse();
      assertThat(secondTarget.isValid()).isFalse();
    }
  }

  @Test
  public void capacityProperty_requiresExplicitTwoAndIgnoresByteBudgetForSingleEntry() {
    properties.set(HardwareRenderingScreenshot.MAX_RETAINED_READER_BYTES, "1");
    try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View view = content(activity.get());
      Surface original = retainReader().getSurface();
      try (ControlledRenderer renderer = new ControlledRenderer(view)) {
        // Malformed and unsupported values preserve the single-entry behavior.
        for (String capacity : new String[] {"1", "garbage", "0", "3"}) {
          properties.set(HardwareRenderingScreenshot.READER_CAPACITY, capacity);
          capture(view).recycle();
          assertThat(renderer.lastTarget).isSameInstanceAs(original);
          assertThat(original.isValid()).isTrue();
        }
        properties.set(HardwareRenderingScreenshot.READER_CAPACITY, "2");
        capture(view).recycle();
        assertThat(original.isValid()).isFalse();
        assertThat(renderer.lastTarget.isValid()).isFalse();
      }
    }
  }

  @Test
  public void reusedReader_crossRootTransparentHolesClearPreviousOpaquePixels() {
    properties.set(HardwareRenderingScreenshot.READER_CAPACITY, "1");
    try (var opaqueActivity = Robolectric.buildActivity(Activity.class).setup().visible();
        var transparentActivity = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View opaque = content(opaqueActivity.get());
      FrameLayout transparent = content(transparentActivity.get());
      View child = new View(transparentActivity.get());
      transparent.setBackgroundColor(Color.TRANSPARENT);
      Surface retainedTarget = retainReader().getSurface();
      try (ControlledRenderer opaqueRenderer = new ControlledRenderer(opaque);
          ControlledRenderer transparentRenderer = new ControlledRenderer(transparent)) {
        opaqueRenderer.setOpaque(true);
        transparentRenderer.setOpaque(false);
        for (int color : new int[] {Color.RED, Color.GREEN, Color.BLUE}) {
          opaque.setBackgroundColor(color);
          capture(opaque).recycle();
          assertThat(opaqueRenderer.lastTarget).isSameInstanceAs(retainedTarget);

          child.setBackgroundColor(Color.YELLOW);
          transparent.addView(child, new FrameLayout.LayoutParams(WIDTH / 2, HEIGHT / 2));
          child.layout(0, 0, WIDTH / 2, HEIGHT / 2);
          assertTransparentHoles(transparent, transparentRenderer, retainedTarget, Color.YELLOW);
          child.setBackgroundColor(Color.CYAN);
          child.invalidate(new Rect(0, 0, 3, 3));
          assertTransparentHoles(transparent, transparentRenderer, retainedTarget, Color.CYAN);
          transparent.removeView(child);
          assertTransparentHoles(
              transparent, transparentRenderer, retainedTarget, Color.TRANSPARENT);
        }
      }
    }
  }

  @Test
  public void twoReaders_crossRootTransparentHolesClearBothPreviouslyOpaqueDimensions() {
    properties.set(HardwareRenderingScreenshot.READER_CAPACITY, "2");
    properties.set(HardwareRenderingScreenshot.MAX_RETAINED_READER_BYTES, "16384");
    try (var firstOpaque = Robolectric.buildActivity(Activity.class).setup().visible();
        var secondOpaque = Robolectric.buildActivity(Activity.class).setup().visible();
        var firstTransparent = Robolectric.buildActivity(Activity.class).setup().visible();
        var secondTransparent = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View opaqueA = content(firstOpaque.get());
      View opaqueB = content(secondOpaque.get());
      opaqueB.layout(0, 0, HEIGHT, WIDTH);
      FrameLayout transparentA = content(firstTransparent.get());
      FrameLayout transparentB = content(secondTransparent.get());
      transparentB.layout(0, 0, HEIGHT, WIDTH);
      for (FrameLayout view : new FrameLayout[] {transparentA, transparentB}) {
        view.setBackgroundColor(Color.TRANSPARENT);
        View child = new View(view.getContext());
        child.setBackgroundColor(Color.YELLOW);
        view.addView(child, new FrameLayout.LayoutParams(8, 8));
        child.layout(0, 0, 8, 8);
      }
      Surface firstTarget = retainReader(WIDTH, HEIGHT, 2, 16384).getSurface();
      Surface secondTarget = retainReader(HEIGHT, WIDTH, 2, 16384).getSurface();
      try (ControlledRenderer opaqueRendererA = new ControlledRenderer(opaqueA);
          ControlledRenderer opaqueRendererB = new ControlledRenderer(opaqueB);
          ControlledRenderer transparentRendererA = new ControlledRenderer(transparentA);
          ControlledRenderer transparentRendererB = new ControlledRenderer(transparentB)) {
        opaqueRendererA.setOpaque(true);
        opaqueRendererB.setOpaque(true);
        transparentRendererA.setOpaque(false);
        transparentRendererB.setOpaque(false);
        for (int color : new int[] {Color.RED, Color.GREEN, Color.BLUE}) {
          opaqueA.setBackgroundColor(color);
          opaqueB.setBackgroundColor(color ^ 0x00ffffff);
          capture(opaqueA).recycle();
          assertThat(opaqueRendererA.lastTarget).isSameInstanceAs(firstTarget);
          capture(opaqueB).recycle();
          assertThat(opaqueRendererB.lastTarget).isSameInstanceAs(secondTarget);
          assertTransparentHoles(transparentA, transparentRendererA, firstTarget, Color.YELLOW);
          assertTransparentHoles(transparentB, transparentRendererB, secondTarget, Color.YELLOW);
        }
      }
    }
  }

  /** Checks a partial draw after an opaque frame, including a hole at the opposite corner. */
  private void assertTransparentHoles(
      View view, ControlledRenderer renderer, Surface retainedTarget, int firstPixel) {
    Bitmap actual = capture(view);
    try {
      assertThat(renderer.lastTarget).isSameInstanceAs(retainedTarget);
      assertThat(renderer.currentTarget).isNull();
      assertThat(actual.getPixel(0, 0)).isEqualTo(firstPixel);
      assertThat(actual.getPixel(view.getWidth() - 1, view.getHeight() - 1))
          .isEqualTo(Color.TRANSPARENT);
      assertMatchesFresh(view, actual);
    } finally {
      actual.recycle();
    }
  }

  /** Uses a small attached content view so every capture key is identical across live windows. */
  private static FrameLayout content(Activity activity) {
    FrameLayout view = new FrameLayout(activity);
    activity.setContentView(view);
    view.layout(0, 0, WIDTH, HEIGHT);
    return view;
  }

  private void assertMatchesFresh(View view) {
    Bitmap actual = capture(view);
    try {
      assertMatchesFresh(view, actual);
    } finally {
      actual.recycle();
    }
  }

  /** Compares every native pixel against the selected implementation's fresh-reader path. */
  private void assertMatchesFresh(View view, Bitmap actual) {
    properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "false");
    try {
      Bitmap expected = capture(view);
      try {
        assertThat(actual.sameAs(expected)).isTrue();
      } finally {
        expected.recycle();
      }
    } finally {
      properties.set(HardwareRenderingScreenshot.REUSE_SCREENSHOT_READER, "true");
    }
  }

  private static Bitmap capture(View view) {
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    try {
      HardwareRenderingScreenshot.takeScreenshot(view, bitmap);
      return bitmap;
    } catch (RuntimeException | Error failure) {
      bitmap.recycle();
      throw failure;
    }
  }

  /** Seeds or inspects native reader identity without adding a production diagnostics API. */
  private static ImageReader retainReader() {
    return retainReader(WIDTH, HEIGHT, 1, Long.MAX_VALUE);
  }

  private static ImageReader retainReader(int width, int height, int capacity, long maxBytes) {
    HardwareScreenshotReaderSession session =
        ReflectionHelpers.getStaticField(HardwareRenderingScreenshot.class, "readerSession");
    try (Lease lease = session.borrow(width, height, PIXEL_LIMIT, capacity, maxBytes)) {
      lease.markReusable();
      return lease.reader();
    }
  }

  private static ViewRootImpl viewRoot(View view) {
    return (ViewRootImpl) view.getRootView().getParent();
  }

  private static Map<ViewRootImpl, HardwareRenderer> rendererMap() {
    return ReflectionHelpers.getStaticField(HardwareRenderingScreenshot.class, "hardwareRenderers");
  }

  /** Exercises actual native rendering, with failures at owned capture and cleanup boundaries. */
  private static final class ControlledRenderer extends HardwareRenderer implements AutoCloseable {
    private final ViewRootImpl root;
    private final HardwareRenderer previous;
    Surface currentTarget;
    Surface lastTarget;
    RuntimeException renderFailure;
    RuntimeException detachFailure;
    int destroyCount;

    ControlledRenderer(View view) {
      root = viewRoot(view);
      previous = rendererMap().put(root, this);
    }

    @Override
    public void setSurface(Surface surface) {
      if (surface == null && detachFailure != null) {
        throw detachFailure;
      }
      super.setSurface(surface);
      currentTarget = surface;
      if (surface != null) {
        lastTarget = surface;
      }
    }

    @Override
    public void setContentRoot(RenderNode root) {
      if (renderFailure != null) {
        throw renderFailure;
      }
      super.setContentRoot(root);
    }

    @Override
    public void destroy() {
      destroyCount++;
      super.destroy();
    }

    @Override
    public void close() {
      if (previous == null) {
        rendererMap().remove(root);
      } else {
        rendererMap().put(root, previous);
      }
      super.setSurface(null);
      destroy();
    }
  }
}
