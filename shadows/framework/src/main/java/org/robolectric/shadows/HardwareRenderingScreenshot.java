package org.robolectric.shadows;

import static android.os.Build.VERSION_CODES.BAKLAVA;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.View;
import android.view.ViewRootImpl;
import com.android.internal.R;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

/**
 * Helper class to provide hardware rendering-based screenshot to {@link ShadowPixelCopy} and {@link
 * ShadowUiAutomation}.
 *
 * <p>On SDK 36, set {@code robolectric.reuseHardwareScreenshotReader=true} to retain one reader
 * between main-thread captures. The per-reader limit defaults to 4,194,304 pixels and can be set
 * with {@code robolectric.hardwareScreenshotReaderMaxPixels}. Set {@code
 * robolectric.hardwareScreenshotReaderCapacity=2} to retain two sizes. Their combined RGBA plane
 * sizes must fit within {@code robolectric.hardwareScreenshotReaderMaxBytes} (20 MiB by default).
 * The native allocator can reserve more memory than the plane sizes indicate.
 */
public final class HardwareRenderingScreenshot {

  // It is important to reuse HardwareRenderer objects, and ensure that after a HardwareRenderer is
  // collected, no associated views in the same View hierarchy will be rendered as well.
  private static final WeakHashMap<ViewRootImpl, HardwareRenderer> hardwareRenderers =
      new WeakHashMap<>();

  private static final HardwareScreenshotReaderSession readerSession =
      new HardwareScreenshotReaderSession();

  static final String PIXEL_COPY_RENDER_MODE = "robolectric.pixelCopyRenderMode";

  static final String USE_EMBEDDED_VIEW_ROOT = "robolectric.useEmbeddedViewRoot";

  static final String REUSE_SCREENSHOT_READER = "robolectric.reuseHardwareScreenshotReader";

  static final String MAX_RETAINED_READER_PIXELS = "robolectric.hardwareScreenshotReaderMaxPixels";

  static final String READER_CAPACITY = "robolectric.hardwareScreenshotReaderCapacity";

  static final String MAX_RETAINED_READER_BYTES = "robolectric.hardwareScreenshotReaderMaxBytes";

  // This limits retained dimensions. A reader can own several native buffers of this size.
  private static final long DEFAULT_MAX_RETAINED_READER_PIXELS = 4_194_304;

  // Capacity two charges both idle and leased readers against this nominal RGBA plane budget.
  private static final long DEFAULT_MAX_RETAINED_READER_BYTES = 20L * 1024 * 1024;

  private HardwareRenderingScreenshot() {}

  /**
   * Indicates whether {@link #takeScreenshot(View, Bitmap)} can run, by validating the API level,
   * the value of the {@link #PIXEL_COPY_RENDER_MODE} property, and the {@link GraphicsMode}.
   */
  static boolean canTakeScreenshot(View view) {
    return RuntimeEnvironment.getApiLevel() >= P
        && "hardware".equalsIgnoreCase(System.getProperty(PIXEL_COPY_RENDER_MODE, "hardware"))
        && ShadowView.useRealGraphics()
        && view.canHaveDisplayList();
  }

  /**
   * Generates a bitmap given the current view using hardware accelerated canvases with native
   * graphics calls. Requires API 28+ (S).
   *
   * <p>This code mirrors the behavior of LayoutLib's RenderSessionImpl.renderAndBuildResult(); see
   * https://googleplex-android.googlesource.com/platform/frameworks/layoutlib/+/refs/heads/master-layoutlib-native/bridge/src/com/android/layoutlib/bridge/impl/RenderSessionImpl.java#573
   */
  static void takeScreenshot(View view, Bitmap destBitmap) {
    capturePlane(
        view,
        plane -> {
          destBitmap.copyPixelsFromBuffer(plane.getBuffer());
          return null;
        });
  }

  /** Consumes the rendered RGBA plane synchronously while its ImageReader owns the pixels. */
  static <T> T capturePlane(View view, Function<Plane, T> consumePlane) {
    // Reusing a target requires the SDK 36 renderer to detach it after each frame.
    if (RuntimeEnvironment.getApiLevel() == BAKLAVA
        && Boolean.getBoolean(REUSE_SCREENSHOT_READER)
        && Looper.myLooper() == Looper.getMainLooper()) {
      return capturePlaneWithReaderLease(view, consumePlane);
    }

    int width = view.getWidth();
    int height = view.getHeight();

    try (ImageReader imageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)) {
      ViewRootImpl viewRootImpl = view.getViewRootImpl();
      Objects.requireNonNull(viewRootImpl, "View not attached");
      Surface surface = imageReader.getSurface();

      try {
        if (RuntimeEnvironment.getApiLevel() >= Q) {
          // HardwareRenderer is only available on API 29+ (Q).
          render(getRenderer(viewRootImpl), surface, view);
        } else {
          // Note this API does not set any light source properties, so it will not render
          // drop shadows.
          Canvas canvas = surface.lockHardwareCanvas();
          view.draw(canvas);
          surface.unlockCanvasAndPost(canvas);
        }
        try (Image nativeImage = imageReader.acquireNextImage()) {
          return consumePlane.apply(nativeImage.getPlanes()[0]);
        }
      } finally {
        surface.release();
      }
    }
  }

  /** Clears sandbox-owned readers through ShadowViewRootImpl's registered reset hook. */
  static void clearReaderSession() {
    readerSession.clear();
  }

  private static <T> T capturePlaneWithReaderLease(View view, Function<Plane, T> consumePlane) {
    long maxPixels = Long.getLong(MAX_RETAINED_READER_PIXELS, DEFAULT_MAX_RETAINED_READER_PIXELS);
    int capacity = Integer.getInteger(READER_CAPACITY, 1);
    long maxBytes =
        capacity == 2
            ? Long.getLong(MAX_RETAINED_READER_BYTES, DEFAULT_MAX_RETAINED_READER_BYTES)
            : Long.MAX_VALUE;
    try (HardwareScreenshotReaderSession.Lease lease =
        readerSession.borrow(view.getWidth(), view.getHeight(), maxPixels, capacity, maxBytes)) {
      ViewRootImpl viewRootImpl =
          Objects.requireNonNull(view.getViewRootImpl(), "View not attached");
      ImageReader reader = lease.reader();
      T result;
      try (CaptureTarget target = new CaptureTarget(getRenderer(viewRootImpl))) {
        render(target.renderer, reader.getSurface(), view);
        try (Image image = reader.acquireNextImage()) {
          result = consumePlane.apply(image.getPlanes()[0]);
        }
      }
      // Image close and target detach must both succeed before returning the reader to the cache.
      lease.markReusable();
      return result;
    }
  }

  private static HardwareRenderer getRenderer(ViewRootImpl viewRootImpl) {
    return hardwareRenderers.computeIfAbsent(
        viewRootImpl,
        k -> {
          // Default to true because Views' RenderNodeAnimators write to the native
          // RootRenderNode state. For highest fidelity, we want to use the
          // HardwareRenderer that is attached to the ViewRootImpl.
          if (Boolean.parseBoolean(System.getProperty(USE_EMBEDDED_VIEW_ROOT, "true"))) {
            ShadowViewRootImpl shadowViewRootImpl = Shadow.extract(viewRootImpl);
            // Using Object here is required to avoid a VerifyError when this lambda class
            // is loaded on SDK < 29, where ThreadedRenderer is not a subclass of
            // HardwareRenderer.
            Object threadedRenderer = shadowViewRootImpl.getThreadedRenderer();
            return (HardwareRenderer) threadedRenderer;
          } else {
            return new HardwareRenderer();
          }
        });
  }

  private static void render(HardwareRenderer renderer, Surface surface, View view) {
    renderer.setSurface(surface);
    setupRendererShadowProperties(renderer, view);
    RenderNode node = getRenderNode(view);
    renderer.setContentRoot(node);
    renderer.createRenderRequest().syncAndDraw();
  }

  /** Detaches the borrowed Surface while preserving the ViewRoot-owned renderer and animations. */
  private static final class CaptureTarget implements AutoCloseable {
    final HardwareRenderer renderer;

    CaptureTarget(HardwareRenderer renderer) {
      this.renderer = renderer;
    }

    @Override
    public void close() {
      // Failure discards the outer reader lease. The ViewRoot-owned renderer may retain the
      // closed target until its next successful attachment or ordinary lifecycle cleanup.
      renderer.setSurface(null);
    }
  }

  private static RenderNode getRenderNode(View view) {
    return ReflectionHelpers.callInstanceMethod(view, "updateDisplayListIfDirty");
  }

  private static void setupRendererShadowProperties(HardwareRenderer renderer, View view) {
    Context context = view.getContext();
    Resources resources = context.getResources();
    DisplayMetrics displayMetrics = resources.getDisplayMetrics();

    // Get the LightSourceGeometry and LightSourceAlpha from resources.
    // The default values are the ones recommended by the getLightSourceGeometry() and
    // getLightSourceAlpha() documentation.
    // This matches LayoutLib's RenderSessionImpl#renderAndBuildResult() implementation.

    TypedArray a = context.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
    float lightX = displayMetrics.widthPixels / 2f;
    float lightY = a.getDimension(R.styleable.Lighting_lightY, 0f);
    float lightZ = a.getDimension(R.styleable.Lighting_lightZ, 600f * displayMetrics.density);
    float lightRadius =
        a.getDimension(R.styleable.Lighting_lightRadius, 800f * displayMetrics.density);
    float ambientShadowAlpha = a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0.039f);
    float spotShadowAlpha = a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0.19f);
    a.recycle();

    renderer.setLightSourceGeometry(lightX, lightY, lightZ, lightRadius);
    renderer.setLightSourceAlpha(ambientShadowAlpha, spotShadowAlpha);
  }
}
