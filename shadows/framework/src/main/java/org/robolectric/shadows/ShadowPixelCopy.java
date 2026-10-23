package org.robolectric.shadows;

import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.robolectric.util.reflector.Reflector.reflector;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.PixelCopy.OnPixelCopyFinishedListener;
import android.view.PixelCopy.Request;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.Window;
import android.view.WindowManagerGlobal;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowWindowManagerGlobal.WindowManagerGlobalReflector;
import org.robolectric.util.PerfStatsCollector;
import org.robolectric.util.reflector.Accessor;
import org.robolectric.util.reflector.Constructor;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import org.robolectric.util.reflector.Static;

/**
 * Shadow for PixelCopy that uses View.draw to create screenshots. The real PixelCopy performs a
 * full hardware capture of the screen at the given location, which is impossible in Robolectric.
 *
 * <p>If listenerThread is backed by a paused looper, make sure to call ShadowLooper.idle() to
 * ensure the screenshot finishes.
 */
@Implements(value = PixelCopy.class, minSdk = O)
public class ShadowPixelCopy {

  @Implementation
  protected static void request(
      SurfaceView source,
      @Nonnull Bitmap dest,
      @Nonnull OnPixelCopyFinishedListener listener,
      @Nonnull Handler listenerThread) {
    if (ShadowView.areRealDrawTraversalsEnabled()) {
      // when realViewDrawTraversals are on, the view will be backed by a valid Surface.
      // so just use that
      reflector(PixelCopyReflector.class).request(source, dest, listener, listenerThread);
    } else {
      takeScreenshot(source, dest, null);
      alertFinished(listener, listenerThread, PixelCopy.SUCCESS);
    }
  }

  @Implementation
  protected static void request(
      @Nonnull SurfaceView source,
      @Nullable Rect srcRect,
      @Nonnull Bitmap dest,
      @Nonnull OnPixelCopyFinishedListener listener,
      @Nonnull Handler listenerThread) {
    if (ShadowView.areRealDrawTraversalsEnabled()) {
      // when realViewDrawTraversals are on, the view will be backed by a valid Surface.
      // so just use that
      reflector(PixelCopyReflector.class).request(source, srcRect, dest, listener, listenerThread);
    } else {
      if (srcRect != null && srcRect.isEmpty()) {
        throw new IllegalArgumentException("sourceRect is empty");
      }
      takeScreenshot(source, dest, srcRect);
      alertFinished(listener, listenerThread, PixelCopy.SUCCESS);
    }
  }

  @Implementation
  protected static void request(
      @Nonnull Window source,
      @Nonnull Bitmap dest,
      @Nonnull OnPixelCopyFinishedListener listener,
      @Nonnull Handler listenerThread) {
    request(source, null, dest, listener, listenerThread);
  }

  @Implementation
  protected static void request(
      @Nonnull Window source,
      @Nullable Rect srcRect,
      @Nonnull Bitmap dest,
      @Nonnull OnPixelCopyFinishedListener listener,
      @Nonnull Handler listenerThread) {
    if (ShadowView.areRealDrawTraversalsEnabled()) {
      // when realViewDrawTraversals are on, the view will be backed by a valid Surface.
      // so just use that
      reflector(PixelCopyReflector.class).request(source, srcRect, dest, listener, listenerThread);
    } else {
      if (srcRect != null && srcRect.isEmpty()) {
        throw new IllegalArgumentException("sourceRect is empty");
      }
      takeScreenshot(source.getDecorView(), dest, srcRect);
      alertFinished(listener, listenerThread, PixelCopy.SUCCESS);
    }
  }

  @Implementation
  protected static void request(
      @Nonnull Surface source,
      @Nullable Rect srcRect,
      @Nonnull Bitmap dest,
      @Nonnull OnPixelCopyFinishedListener listener,
      @Nonnull Handler listenerThread) {
    if (srcRect != null && srcRect.isEmpty()) {
      throw new IllegalArgumentException("sourceRect is empty");
    }
    if (ShadowView.areRealDrawTraversalsEnabled()) {
      captureImageFromSurface(source, dest, srcRect);
    } else {
      View view = findViewForSurface(requireNonNull(source));

      Rect adjustedSrcRect = null;
      if (srcRect != null) {
        adjustedSrcRect = new Rect(srcRect);
        int[] locationInSurface = ShadowView.getLocationInSurfaceCompat(view);
        // offset the srcRect by the decor view's location in the surface
        adjustedSrcRect.offset(-locationInSurface[0], -locationInSurface[1]);
      }
      takeScreenshot(view, dest, adjustedSrcRect);
    }
    alertFinished(listener, listenerThread, PixelCopy.SUCCESS);
  }

  @Implementation(minSdk = UPSIDE_DOWN_CAKE)
  protected static void request(
      @ClassName("android.view.PixelCopy$Request") Object requestObject,
      Executor callbackExecutor,
      Consumer</*android.view.PixelCopy$Result*/ ?> listener) {
    PixelCopy.Request request = (PixelCopy.Request) requestObject;
    RequestReflector requestReflector = reflector(RequestReflector.class, request);
    //noinspection Convert2Lambda
    OnPixelCopyFinishedListener legacyListener =
        new OnPixelCopyFinishedListener() {
          @Override
          public void onPixelCopyFinished(int copyResult) {
            ((Consumer<PixelCopy.Result>) listener)
                .accept(
                    reflector(ResultReflector.class)
                        .newResult(copyResult, request.getDestinationBitmap()));
          }
        };
    Rect adjustedSrcRect =
        reflector(PixelCopyReflector.class)
            .adjustSourceRectForInsets(requestReflector.getSourceInsets(), request.getSourceRect());
    PixelCopy.request(
        requestReflector.getSource(),
        adjustedSrcRect,
        request.getDestinationBitmap(),
        legacyListener,
        new Handler(Looper.getMainLooper()));
  }

  private static View findViewForSurface(Surface source) {
    for (View windowView :
        reflector(WindowManagerGlobalReflector.class, WindowManagerGlobal.getInstance())
            .getWindowViews()) {
      ShadowViewRootImpl shadowViewRoot = Shadow.extract(windowView.getViewRootImpl());
      if (source.equals(shadowViewRoot.getSurface())) {
        return windowView;
      }
    }

    throw new IllegalArgumentException(
        "Could not find view for surface. Is it attached to a window?");
  }

  /**
   * Method to obtain a Bitmap when using real View draw traversals. This method just takes the
   * image already produced by the draw traversal as opposed to doing a re-draw.
   */
  static void captureImageFromSurface(Surface surface, Bitmap destBitmap, @Nullable Rect srcRect) {
    validateBitmap(destBitmap);
    checkState(Looper.getMainLooper().isCurrentThread());

    ShadowNativeSurface shadowSurface = Shadow.extract(surface);
    ImageReader imageReader = shadowSurface.getContainerImageReader();

    if (doDimensionsMatch(destBitmap, imageReader, srcRect)) {
      // fast path, just copy to destination screenshot, no need to resize
      copyFromImageReader(imageReader, destBitmap);
    } else {
      // need to resize bitmap
      Bitmap intermediateBitmap =
          Bitmap.createBitmap(
              imageReader.getWidth(), imageReader.getHeight(), Bitmap.Config.ARGB_8888);

      copyFromImageReader(imageReader, intermediateBitmap);
      Rect dst = new Rect(0, 0, destBitmap.getWidth(), destBitmap.getHeight());
      Canvas resizingCanvas = new Canvas(destBitmap);
      Paint paint = new Paint();
      resizingCanvas.drawBitmap(intermediateBitmap, srcRect, dst, paint);
      intermediateBitmap.recycle();
    }
  }

  /**
   * Determine if the image reader dimensions exactly match the given bitmap and cropping srcRect
   */
  private static boolean doDimensionsMatch(
      Bitmap destBitmap, ImageReader imageReader, Rect srcRect) {
    if (destBitmap.getWidth() != imageReader.getWidth()) {
      return false;
    }
    if (destBitmap.getHeight() != imageReader.getHeight()) {
      return false;
    }
    if (srcRect == null) {
      // abort - srcRect is null, dimensions match!
      return true;
    }
    if (srcRect.left != 0) {
      return false;
    }
    if (srcRect.top != 0) {
      return false;
    }
    if (srcRect.right != imageReader.getWidth()) {
      return false;
    }
    if (srcRect.bottom != imageReader.getHeight()) {
      return false;
    }
    return true;
  }

  private static void copyFromImageReader(ImageReader imageReader, Bitmap bitmap) {
    try (Image nativeImage = imageReader.acquireNextImage()) {
      Plane[] planes = nativeImage.getPlanes();
      bitmap.copyPixelsFromBuffer(planes[0].getBuffer());
    }
  }

  private static void takeScreenshot(View view, Bitmap screenshot, @Nullable Rect srcRect) {
    validateBitmap(screenshot);

    int width = view.getWidth();
    int height = view.getHeight();
    boolean hardware = HardwareRenderingScreenshot.canTakeScreenshot(view);
    Bitmap bitmap;
    if (hardware) {
      bitmap =
          PerfStatsCollector.getInstance()
              .measure(
                  "ShadowPixelCopy-Hardware",
                  () ->
                      HardwareRenderingScreenshot.capturePlane(
                          view, plane -> copyPlane(plane, screenshot, srcRect, width, height)));
      if (bitmap == null) {
        return;
      }
    } else {
      bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    try {
      if (hardware) {
        drawScreenshot(bitmap, srcRect, screenshot);
      } else {
        Canvas canvas = new Canvas(bitmap);
        try {
          PerfStatsCollector.getInstance()
              .measure("ShadowPixelCopy-Software", () -> view.draw(canvas));
          drawScreenshot(bitmap, srcRect, screenshot);
        } finally {
          // Detach after the copy. The View may have left saved layers on its Canvas.
          canvas.setBitmap(null);
        }
      }
    } finally {
      bitmap.recycle();
    }
  }

  /**
   * Returns a full-frame bitmap for the Canvas fallback, or null if the destination is complete.
   */
  @Nullable
  private static Bitmap copyPlane(
      Plane plane, Bitmap destination, @Nullable Rect srcRect, int width, int height) {
    ByteBuffer pixels = plane.getBuffer();
    if (copyOpaquePlane(
        pixels,
        plane.getRowStride(),
        plane.getPixelStride(),
        destination,
        srcRect,
        width,
        height)) {
      return null;
    }
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    try {
      bitmap.copyPixelsFromBuffer(pixels);
      return bitmap;
    } catch (RuntimeException | Error failure) {
      bitmap.recycle();
      throw failure;
    }
  }

  private static void drawScreenshot(Bitmap bitmap, @Nullable Rect srcRect, Bitmap destination) {
    Canvas canvas = new Canvas(destination);
    try {
      Rect dst = new Rect(0, 0, destination.getWidth(), destination.getHeight());
      canvas.drawBitmap(bitmap, srcRect, dst, new Paint());
    } finally {
      // The caller owns the destination and can recycle it as soon as PixelCopy returns.
      canvas.setBitmap(null);
    }
  }

  /**
   * Copies an opaque, unscaled RGBA rectangle when SrcOver would overwrite every destination pixel.
   * Check every selected alpha before modifying the destination. Rows with gaps need a packed copy.
   */
  static boolean copyOpaquePlane(
      ByteBuffer pixels,
      int rowStride,
      int pixelStride,
      Bitmap destination,
      @Nullable Rect sourceRect,
      int width,
      int height) {
    int left = sourceRect == null ? 0 : sourceRect.left;
    int top = sourceRect == null ? 0 : sourceRect.top;
    int right = sourceRect == null ? width : sourceRect.right;
    int bottom = sourceRect == null ? height : sourceRect.bottom;
    if (width <= 0
        || height <= 0
        || left < 0
        || top < 0
        || right > width
        || bottom > height
        || right <= left
        || bottom <= top) {
      return false;
    }
    int cropWidth = right - left;
    int cropHeight = bottom - top;
    long cropRowBytes = (long) cropWidth * 4;
    if (destination.getWidth() != cropWidth
        || destination.getHeight() != cropHeight
        || !destination.isMutable()
        || destination.getConfig() != Bitmap.Config.ARGB_8888
        || !destination.isPremultiplied()
        || !ColorSpace.get(ColorSpace.Named.SRGB).equals(destination.getColorSpace())
        || pixelStride != 4
        || rowStride < (long) width * 4
        || destination.getRowBytes() != cropRowBytes) {
      return false;
    }
    long byteCount = cropRowBytes * cropHeight;
    // The last selected pixel must fit; ImageReader may omit padding after its final row.
    long requiredBytes = (long) (bottom - 1) * rowStride + (long) right * 4;
    if (byteCount > Integer.MAX_VALUE || requiredBytes > pixels.remaining()) {
      return false;
    }
    int position = pixels.position();
    // ImageReader produces RGBA_8888. Only selected pixels contribute to the original SrcOver draw.
    for (int y = top; y < bottom; y++) {
      int rowStart = (int) (position + (long) y * rowStride + (long) left * 4);
      for (int offset = 3; offset < cropRowBytes; offset += 4) {
        if (pixels.get(rowStart + offset) != (byte) 0xff) {
          return false;
        }
      }
    }
    if (left == 0 && top == 0 && right == width && bottom == height && rowStride == cropRowBytes) {
      destination.copyPixelsFromBuffer(pixels);
    } else if (cropHeight == 1 || rowStride == cropRowBytes) {
      // A single row or a run of complete rows is already packed.
      ByteBuffer source = pixels.duplicate();
      source.position(position + top * rowStride + left * 4);
      destination.copyPixelsFromBuffer(source);
    } else {
      // Pack rows whose stride includes pixels or padding outside the crop.
      byte[] croppedPixels = new byte[(int) byteCount];
      ByteBuffer source = pixels.duplicate();
      for (int y = top; y < bottom; y++) {
        source.position((int) (position + (long) y * rowStride + (long) left * 4));
        source.get(croppedPixels, (int) ((y - top) * cropRowBytes), (int) cropRowBytes);
      }
      destination.copyPixelsFromBuffer(ByteBuffer.wrap(croppedPixels));
    }
    return true;
  }

  private static void alertFinished(
      OnPixelCopyFinishedListener listener, Handler listenerThread, int result) {
    if (listenerThread.getLooper() == Looper.getMainLooper()) {
      listener.onPixelCopyFinished(result);
      return;
    }
    listenerThread.post(() -> listener.onPixelCopyFinished(result));
  }

  private static Bitmap validateBitmap(Bitmap bitmap) {
    if (bitmap == null) {
      throw new IllegalArgumentException("Bitmap cannot be null");
    }
    if (bitmap.isRecycled()) {
      throw new IllegalArgumentException("Bitmap is recycled");
    }
    if (!bitmap.isMutable()) {
      throw new IllegalArgumentException("Bitmap is immutable");
    }
    return bitmap;
  }

  @Implements(
      value = PixelCopy.Request.Builder.class,
      minSdk = UPSIDE_DOWN_CAKE,
      isInAndroidSdk = false)
  public static class ShadowPixelCopyRequestBuilder {

    // TODO(brettchabot): remove once robolectric has proper support for initializing a Surface
    // for now, this copies Android implementation and just omits the valid surface check
    @Implementation
    protected static PixelCopy.Request.Builder ofWindow(View source) {
      if (ShadowView.areRealDrawTraversalsEnabled()) {
        return reflector(BuilderReflector.class).ofWindow(source);
      } else {
        if (source == null || !source.isAttachedToWindow()) {
          throw new IllegalArgumentException("View must not be null & must be attached to window");
        }
        final Rect insets = new Rect();
        Surface surface = null;
        final ViewRootImpl root = source.getViewRootImpl();
        if (root != null) {
          surface = root.mSurface;
          insets.set(root.mWindowAttributes.surfaceInsets);
        }
        PixelCopy.Request request = reflector(RequestReflector.class).newRequest(surface, insets);
        return reflector(BuilderReflector.class).newBuilder(request);
      }
    }
  }

  @ForType(PixelCopy.class)
  private interface PixelCopyReflector {
    @Static
    Rect adjustSourceRectForInsets(Rect insets, Rect srcRect);

    @Static
    @Direct
    void request(
        SurfaceView source,
        Bitmap dest,
        OnPixelCopyFinishedListener listener,
        Handler listenerThread);

    @Static
    @Direct
    void request(
        SurfaceView source,
        Rect srcRect,
        Bitmap dest,
        OnPixelCopyFinishedListener listener,
        Handler listenerThread);

    @Static
    @Direct
    void request(
        Window source,
        Rect srcRect,
        Bitmap dest,
        OnPixelCopyFinishedListener listener,
        Handler listenerThread);
  }

  @ForType(PixelCopy.Request.Builder.class)
  private interface BuilderReflector {
    @Constructor
    PixelCopy.Request.Builder newBuilder(PixelCopy.Request request);

    @Static
    @Direct
    Request.Builder ofWindow(View source);
  }

  @ForType(PixelCopy.Request.class)
  private interface RequestReflector {
    @Constructor
    PixelCopy.Request newRequest(Surface surface, Rect insets);

    @Accessor("mSource")
    Surface getSource();

    @Accessor("mSourceInsets")
    Rect getSourceInsets();
  }

  @ForType(PixelCopy.Result.class)
  private interface ResultReflector {
    @Constructor
    PixelCopy.Result newResult(int copyResult, Bitmap bitmap);
  }
}
