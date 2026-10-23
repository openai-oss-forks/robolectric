package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image.Plane;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.junit.rules.SetSystemPropertyRule;

/** Native pixel equivalence for opaque hardware rectangle copies and their fallback boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShadowPixelCopyOpaquePlaneTest {
  @Rule public SetSystemPropertyRule properties = new SetSystemPropertyRule();

  @Before
  public void selectLegacyHardwareCapture() {
    properties.set("robolectric.pixelCopyRenderMode", "hardware");
    properties.set("robolectric.useRealDrawTraversals", "false");
    ShadowView.setUseRealDrawTraversals(false);
  }

  @Test
  public void opaquePlane_matchesNativeSrcOverAcrossChannelsAndDestinationDensities() {
    PackedPlane plane = new PackedPlane(256, 256, 7);
    for (int density : new int[] {0, 160, 480}) {
      Bitmap actual = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
      Bitmap expected = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
      Bitmap intermediate = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
      try {
        actual.setDensity(density);
        expected.setDensity(density);
        actual.eraseColor(0x7fabcdef);
        expected.eraseColor(0x7fabcdef);
        plane.pixels.position(7);
        intermediate.copyPixelsFromBuffer(plane.pixels);
        Canvas canvas = new Canvas(expected);
        try {
          canvas.drawBitmap(intermediate, null, new Rect(0, 0, 256, 256), new Paint());
        } finally {
          canvas.setBitmap(null);
        }
        plane.pixels.position(7);

        assertThat(plane.copyTo(actual, null, 256, 256)).isTrue();

        assertThat(actual.sameAs(expected)).isTrue();
        assertThat(actual.getDensity()).isEqualTo(density);
        assertThat(plane.pixels.position()).isEqualTo(7 + 256 * 256 * 4);
        assertThat(actual.isRecycled()).isFalse();
      } finally {
        actual.recycle();
        expected.recycle();
        intermediate.recycle();
      }
    }
  }

  /** Selected pixels use the same density-independent SrcOver crop as the native reference. */
  @Test
  public void opaqueCrop_matchesNativeSrcOverWithOffsetsPaddingAndDestinationDensities() {
    Rect crop = new Rect(3, 2, 11, 9);
    Bitmap intermediate = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
    intermediate.copyPixelsFromBuffer(new PackedPlane(16, 12, 0).pixels);
    try {
      for (int padding : new int[] {0, 12}) {
        PackedPlane plane = new PackedPlane(16, 12, 5, padding);
        // Unselected pixels and row padding may be translucent without affecting the crop.
        plane.pixels.put(5 + 3, (byte) 0);
        plane.pixels.put(5 + 11 * plane.rowStride + 15 * 4 + 3, (byte) 0);
        for (int density : new int[] {0, 160, 480}) {
          Bitmap actual = Bitmap.createBitmap(8, 7, Bitmap.Config.ARGB_8888);
          Bitmap expected = Bitmap.createBitmap(8, 7, Bitmap.Config.ARGB_8888);
          try {
            actual.setDensity(density);
            expected.setDensity(density);
            actual.eraseColor(0x7fabcdef);
            expected.eraseColor(0x7fabcdef);
            Canvas canvas = new Canvas(expected);
            try {
              canvas.drawBitmap(intermediate, crop, new Rect(0, 0, 8, 7), new Paint());
            } finally {
              canvas.setBitmap(null);
            }

            assertThat(plane.copyTo(actual, crop, 16, 12)).isTrue();

            assertThat(actual.sameAs(expected)).isTrue();
            assertThat(actual.getDensity()).isEqualTo(density);
            assertThat(plane.pixels.position()).isEqualTo(5);
            assertThat(actual.isRecycled()).isFalse();
            actual.eraseColor(Color.GREEN);
            assertThat(actual.getPixel(7, 6)).isEqualTo(Color.GREEN);
          } finally {
            actual.recycle();
            expected.recycle();
          }
        }
      }
    } finally {
      intermediate.recycle();
    }
  }

  /** Eligibility must finish before caller-owned pixels or source state can change. */
  @Test
  public void contiguousCropsRespectPlanePositionAndSourceOffset() {
    Bitmap source = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
    try {
      source.copyPixelsFromBuffer(new PackedPlane(16, 12, 0).pixels);
      for (Rect crop : new Rect[] {new Rect(3, 2, 12, 3), new Rect(0, 4, 16, 9)}) {
        PackedPlane plane = new PackedPlane(16, 12, 7, crop.height() == 1 ? 12 : 0);
        Bitmap bitmap = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888);
        try {
          assertThat(plane.copyTo(bitmap, crop, 16, 12)).isTrue();
          for (int y = crop.top; y < crop.bottom; y++) {
            for (int x = crop.left; x < crop.right; x++) {
              assertThat(bitmap.getPixel(x - crop.left, y - crop.top))
                  .isEqualTo(source.getPixel(x, y));
            }
          }
          assertThat(plane.pixels.position()).isEqualTo(7);
        } finally {
          bitmap.recycle();
        }
      }
    } finally {
      source.recycle();
    }
  }

  @Test
  public void nonopaqueFinalCropPixel_preservesWholeDestinationAndSourceForFallback() {
    PackedPlane plane = new PackedPlane(16, 12, 5, 12);
    Rect crop = new Rect(3, 2, 11, 9);
    Bitmap destination = Bitmap.createBitmap(8, 7, Bitmap.Config.ARGB_8888);
    Bitmap unchanged = Bitmap.createBitmap(8, 7, Bitmap.Config.ARGB_8888);
    try {
      destination.eraseColor(0x7fabcdef);
      unchanged.eraseColor(0x7fabcdef);
      for (int alpha : new int[] {0, 1, 127, 254}) {
        plane.pixels.put(5 + 8 * plane.rowStride + 10 * 4 + 3, (byte) alpha);

        assertThat(plane.copyTo(destination, crop, 16, 12)).isFalse();

        assertThat(destination.sameAs(unchanged)).isTrue();
        assertThat(plane.pixels.position()).isEqualTo(5);
        assertThat(plane.pixels.limit()).isEqualTo(plane.pixels.capacity());
      }
    } finally {
      destination.recycle();
      unchanged.recycle();
    }
  }

  /** Invalid geometry and rectangles that require scaling retain Canvas handling. */
  @Test
  public void invalidOrScaledCrop_preservesDestinationAndBufferForFallback() {
    PackedPlane plane = new PackedPlane(16, 12, 5, 12);
    Bitmap destination = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
    Bitmap unchanged = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
    try {
      destination.eraseColor(Color.BLUE);
      unchanged.eraseColor(Color.BLUE);
      for (Rect crop :
          new Rect[] {
            new Rect(-1, 1, 7, 7),
            new Rect(9, 1, 17, 7),
            new Rect(1, -1, 9, 5),
            new Rect(1, 7, 9, 13),
            new Rect(1, 1, 1, 7),
            new Rect(9, 7, 1, 1),
            new Rect(Integer.MIN_VALUE, 1, Integer.MIN_VALUE + 8, 7),
            new Rect(Integer.MAX_VALUE - 8, 1, Integer.MAX_VALUE, 7),
            new Rect(1, 1, 10, 7),
            new Rect(1, 1, 9, 8)
          }) {
        assertThat(plane.copyTo(destination, crop, 16, 12)).isFalse();

        assertThat(destination.sameAs(unchanged)).isTrue();
        assertThat(plane.pixels.position()).isEqualTo(5);
      }
    } finally {
      destination.recycle();
      unchanged.recycle();
    }
  }

  /** Readable plane bytes may exclude padding after the final selected row. */
  @Test
  public void cropBufferBounds_requireSelectedBytesAndPermitMissingFinalPadding() {
    PackedPlane plane = new PackedPlane(16, 12, 5, 12);
    Rect crop = new Rect(3, 5, 16, 12);
    Bitmap sourcePixels = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
    Bitmap destination = Bitmap.createBitmap(13, 7, Bitmap.Config.ARGB_8888);
    Bitmap unchanged = Bitmap.createBitmap(13, 7, Bitmap.Config.ARGB_8888);
    try {
      // The native buffer transfer defines the reference's packed channel order.
      sourcePixels.copyPixelsFromBuffer(new PackedPlane(16, 12, 0).pixels);
      destination.eraseColor(Color.BLUE);
      unchanged.eraseColor(Color.BLUE);
      int rowStride = plane.rowStride;
      for (int invalidStride : new int[] {-1, 16 * 4 - 1, Integer.MAX_VALUE}) {
        plane.rowStride = invalidStride;
        assertThat(plane.copyTo(destination, crop, 16, 12)).isFalse();
      }
      plane.rowStride = rowStride;
      plane.pixelStride = 8;
      assertThat(plane.copyTo(destination, crop, 16, 12)).isFalse();
      plane.pixelStride = 4;
      assertThat(plane.copyTo(destination, crop, Integer.MAX_VALUE, Integer.MAX_VALUE)).isFalse();
      int selectedEnd = 5 + 11 * rowStride + 16 * 4;
      plane.pixels.limit(selectedEnd - 1);
      assertThat(plane.copyTo(destination, crop, 16, 12)).isFalse();
      assertThat(destination.sameAs(unchanged)).isTrue();
      assertThat(plane.pixels.position()).isEqualTo(5);

      plane.pixels.limit(selectedEnd);

      assertThat(plane.copyTo(destination, crop, 16, 12)).isTrue();
      assertThat(destination.getPixel(0, 0)).isEqualTo(sourcePixels.getPixel(3, 5));
      assertThat(destination.getPixel(12, 6)).isEqualTo(sourcePixels.getPixel(15, 11));
      assertThat(plane.pixels.position()).isEqualTo(5);
      assertThat(plane.pixels.limit()).isEqualTo(selectedEnd);
    } finally {
      sourcePixels.recycle();
      destination.recycle();
      unchanged.recycle();
    }
  }

  @Test
  public void nonopaqueFinalPixel_preservesDestinationAndBufferForFallback() {
    PackedPlane plane = new PackedPlane(16, 12, 3);
    Bitmap destination = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
    try {
      for (int alpha = 0; alpha < 255; alpha++) {
        plane.pixels.put(plane.pixels.limit() - 1, (byte) alpha);
        destination.eraseColor(Color.GREEN);

        assertThat(plane.copyTo(destination, null, 16, 12)).isFalse();

        assertThat(plane.pixels.position()).isEqualTo(3);
        assertThat(destination.getPixel(0, 0)).isEqualTo(Color.GREEN);
        assertThat(destination.getPixel(15, 11)).isEqualTo(Color.GREEN);
      }
    } finally {
      destination.recycle();
    }
  }

  @Test
  public void incompatibleLayoutOrCrop_keepsOriginalReadAndConversion() {
    PackedPlane plane = new PackedPlane(16, 12, 5);
    Bitmap destination = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
    try {
      destination.eraseColor(Color.BLUE);
      for (Rect rect :
          new Rect[] {new Rect(1, 0, 16, 12), new Rect(0, 0, 15, 12), new Rect(0, 1, 16, 12)}) {
        assertThat(plane.copyTo(destination, rect, 16, 12)).isFalse();
      }
      assertThat(plane.copyTo(destination, null, 15, 12)).isFalse();
      plane.rowStride += 4;
      assertThat(plane.copyTo(destination, null, 16, 12)).isFalse();
      plane.rowStride -= 4;
      plane.pixelStride = 8;
      assertThat(plane.copyTo(destination, null, 16, 12)).isFalse();
      plane.pixelStride = 4;
      plane.pixels.limit(plane.pixels.limit() - 1);
      assertThat(plane.copyTo(destination, null, 16, 12)).isFalse();
      plane.pixels.limit(plane.pixels.capacity());
      destination.setPremultiplied(false);
      assertThat(plane.copyTo(destination, null, 16, 12)).isFalse();
      assertThat(plane.pixels.position()).isEqualTo(5);
      assertThat(destination.getPixel(15, 11)).isEqualTo(Color.BLUE);
    } finally {
      destination.recycle();
    }
  }

  @Test
  public void otherDestinationColorSpacesAndFormats_keepCanvasConversion() {
    PackedPlane plane = new PackedPlane(16, 12, 0);
    Bitmap wideGamut =
        Bitmap.createBitmap(
            16, 12, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
    Bitmap rgb565 = Bitmap.createBitmap(16, 12, Bitmap.Config.RGB_565);
    try {
      assertThat(plane.copyTo(wideGamut, null, 16, 12)).isFalse();
      assertThat(plane.copyTo(rgb565, null, 16, 12)).isFalse();
      assertThat(plane.pixels.position()).isEqualTo(0);
    } finally {
      wideGamut.recycle();
      rgb565.recycle();
    }
  }

  @Test
  public void fullHardwareCapture_matchesIntermediateCopyAndCompletesBeforeCallback() {
    try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View decor = controller.get().getWindow().getDecorView();
      decor.layout(0, 0, 48, 32);
      decor.setBackgroundColor(0xff19a3e7);
      Bitmap intermediate = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
      Bitmap expected = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
      Bitmap actual = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
      try {
        HardwareRenderingScreenshot.takeScreenshot(decor, intermediate);
        expected.eraseColor(Color.MAGENTA);
        actual.eraseColor(Color.MAGENTA);
        Canvas canvas = new Canvas(expected);
        try {
          canvas.drawBitmap(intermediate, null, new Rect(0, 0, 48, 32), new Paint());
        } finally {
          canvas.setBitmap(null);
        }
        AtomicInteger calls = new AtomicInteger();
        PixelCopy.request(
            controller.get().getWindow(),
            new Rect(0, 0, 48, 32),
            actual,
            result -> {
              assertThat(result).isEqualTo(PixelCopy.SUCCESS);
              assertThat(actual.sameAs(expected)).isTrue();
              calls.incrementAndGet();
            },
            new Handler(Looper.getMainLooper()));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(actual.isRecycled()).isFalse();
        actual.eraseColor(Color.GREEN);
        assertThat(actual.getPixel(0, 0)).isEqualTo(Color.GREEN);
      } finally {
        intermediate.recycle();
        expected.recycle();
        actual.recycle();
      }
    }
  }

  /** Direct copies and Canvas fallbacks both finish before notifying the caller. */
  @Test
  public void croppedHardwareCaptureAndFallbacks_matchNativeCanvasBeforeCallback() {
    try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View decor = controller.get().getWindow().getDecorView();
      decor.layout(0, 0, 48, 32);
      Rect crop = new Rect(7, 3, 37, 23);
      for (int background : new int[] {0xff19a3e7, 0x8019a3e7}) {
        decor.setBackgroundColor(background);
        Bitmap intermediate = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
        try {
          HardwareRenderingScreenshot.takeScreenshot(decor, intermediate);
          for (int scale : new int[] {1, 2}) {
            int width = crop.width() / scale;
            int height = crop.height() / scale;
            Bitmap expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Bitmap actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
              expected.eraseColor(Color.MAGENTA);
              actual.eraseColor(Color.MAGENTA);
              Canvas canvas = new Canvas(expected);
              try {
                canvas.drawBitmap(intermediate, crop, new Rect(0, 0, width, height), new Paint());
              } finally {
                canvas.setBitmap(null);
              }
              AtomicInteger calls = new AtomicInteger();
              PixelCopy.request(
                  controller.get().getWindow(),
                  crop,
                  actual,
                  result -> {
                    assertThat(result).isEqualTo(PixelCopy.SUCCESS);
                    assertThat(actual.sameAs(expected)).isTrue();
                    calls.incrementAndGet();
                  },
                  new Handler(Looper.getMainLooper()));
              assertThat(calls.get()).isEqualTo(1);
              assertThat(actual.isRecycled()).isFalse();
              actual.eraseColor(Color.GREEN);
              assertThat(actual.getPixel(width - 1, height - 1)).isEqualTo(Color.GREEN);
            } finally {
              expected.recycle();
              actual.recycle();
            }
          }
        } finally {
          intermediate.recycle();
        }
      }
    }
  }

  @Test
  public void planeConsumerFailure_closesOwnedImageAndAllowsNextCapture() {
    try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
      View decor = controller.get().getWindow().getDecorView();
      decor.layout(0, 0, 16, 12);
      decor.setBackgroundColor(Color.RED);
      Bitmap expected = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
      HardwareRenderingScreenshot.takeScreenshot(decor, expected);
      AtomicReference<Plane> retainedPlane = new AtomicReference<>();
      RuntimeException failure = new RuntimeException("consumer failed");

      RuntimeException actual =
          assertThrows(
              RuntimeException.class,
              () ->
                  HardwareRenderingScreenshot.capturePlane(
                      decor,
                      plane -> {
                        retainedPlane.set(plane);
                        throw failure;
                      }));

      assertThat(actual).isSameInstanceAs(failure);
      assertThrows(IllegalStateException.class, () -> retainedPlane.get().getBuffer());
      Bitmap next = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
      try {
        HardwareRenderingScreenshot.takeScreenshot(decor, next);
        assertThat(next.sameAs(expected)).isTrue();
      } finally {
        next.recycle();
        expected.recycle();
      }
    }
  }

  /** Models RGBA ImageReader bytes with a nonzero position and optional transparent row padding. */
  private static final class PackedPlane {
    final ByteBuffer pixels;
    int rowStride;
    int pixelStride = 4;

    PackedPlane(int width, int height, int prefix) {
      this(width, height, prefix, 0);
    }

    /** Leaves padding transparent so eligibility scans must follow the declared row stride. */
    PackedPlane(int width, int height, int prefix, int rowPadding) {
      rowStride = width * 4 + rowPadding;
      pixels = ByteBuffer.allocateDirect(prefix + rowStride * height);
      for (int y = 0; y < height; y++) {
        pixels.position(prefix + y * rowStride);
        for (int x = 0; x < width; x++) {
          pixels.put((byte) x).put((byte) y).put((byte) (x ^ y)).put((byte) 0xff);
        }
      }
      pixels.position(prefix);
    }

    boolean copyTo(Bitmap bitmap, Rect rect, int width, int height) {
      return ShadowPixelCopy.copyOpaquePlane(
          pixels, rowStride, pixelStride, bitmap, rect, width, height);
    }
  }
}
