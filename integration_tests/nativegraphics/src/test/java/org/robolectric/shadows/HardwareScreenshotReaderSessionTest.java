package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.HardwareScreenshotReaderSession.Lease;

/** Native ownership checks for the bounded screenshot reader cache and its exclusive leases. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HardwareScreenshotReaderSessionTest {
  private final HardwareScreenshotReaderSession session = new HardwareScreenshotReaderSession();

  @After
  public void clearSession() {
    session.clear();
  }

  @Test
  public void sameDimensions_reuseReaderAndCloseEachImageBeforeNextLease() {
    ImageReader first = null;
    for (int i = 0; i < 4; i++) {
      Plane plane;
      try (Lease lease = session.borrow(16, 12, 1024)) {
        if (first == null) {
          first = lease.reader();
        }
        assertThat(lease.reader()).isSameInstanceAs(first);
        assertThat(lease.reader().getMaxImages()).isEqualTo(1);
        try (Image image = lease.reader().acquireNextImage()) {
          plane = image.getPlanes()[0];
          assertThat(plane.getBuffer()).isNotNull();
        }
        assertThrows(IllegalStateException.class, plane::getBuffer);
        lease.markReusable();
      }
    }
  }

  @Test
  public void changedDimensionsAndSmallerBudget_closePreviousReader() {
    ImageReader first = retain(16, 12, 1024);
    Surface firstSurface = first.getSurface();
    ImageReader second = retain(24, 16, 1024);
    Surface secondSurface = second.getSurface();
    assertThat(firstSurface.isValid()).isFalse();
    assertThat(second).isNotSameInstanceAs(first);

    ImageReader third = retain(8, 8, 64);

    assertThat(secondSurface.isValid()).isFalse();
    assertThat(third.getSurface().isValid()).isTrue();
  }

  @Test
  public void oversizedCapture_closesTemporaryReaderAndPreservesEligibleEntry() {
    ImageReader retained = retain(16, 12, 1024);
    Surface temporarySurface;
    try (Lease temporary = session.borrow(64, 64, 1024)) {
      temporarySurface = temporary.reader().getSurface();
      assertThat(temporary.reader()).isNotSameInstanceAs(retained);
      temporary.markReusable();
    }

    assertThat(temporarySurface.isValid()).isFalse();
    assertThat(retain(16, 12, 1024)).isSameInstanceAs(retained);
  }

  @Test
  public void nestedCapture_ownsTemporaryReaderWithoutClosingOuterLease() {
    ImageReader outerReader;
    Surface nestedSurface;
    try (Lease outer = session.borrow(16, 12, 1024)) {
      outerReader = outer.reader();
      try (Lease nested = session.borrow(16, 12, 1024)) {
        assertThat(nested.reader()).isNotSameInstanceAs(outerReader);
        nestedSurface = nested.reader().getSurface();
        nested.markReusable();
      }
      assertThat(nestedSurface.isValid()).isFalse();
      assertThat(outerReader.getSurface().isValid()).isTrue();
      outer.markReusable();
    }

    assertThat(retain(16, 12, 1024)).isSameInstanceAs(outerReader);
  }

  @Test
  public void offMainBorrow_ownsTemporaryReaderAndLeavesMainThreadEntry() throws Exception {
    ImageReader retained = retain(16, 12, 1024);
    AtomicReference<Surface> temporarySurface = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try (Lease temporary = session.borrow(16, 12, 1024)) {
                assertThat(temporary.reader()).isNotSameInstanceAs(retained);
                temporarySurface.set(temporary.reader().getSurface());
                temporary.markReusable();
              } catch (Throwable throwable) {
                failure.set(throwable);
              }
            });
    thread.start();
    thread.join();

    assertThat(failure.get()).isNull();
    assertThat(temporarySurface.get().isValid()).isFalse();
    assertThat(retain(16, 12, 1024)).isSameInstanceAs(retained);
  }

  @Test
  public void clearWhileLeased_defersCloseAndPreservesNewGeneration() {
    ImageReader replacement;
    Surface activeSurface;
    try (Lease active = session.borrow(16, 12, 1024)) {
      activeSurface = active.reader().getSurface();
      session.clear();
      assertThat(activeSurface.isValid()).isTrue();
      replacement = retain(16, 12, 1024);
      assertThat(replacement).isNotSameInstanceAs(active.reader());
      active.markReusable();
    }

    assertThat(activeSurface.isValid()).isFalse();
    assertThat(retain(16, 12, 1024)).isSameInstanceAs(replacement);
    Surface replacementSurface = replacement.getSurface();
    session.clear();
    session.clear();
    assertThat(replacementSurface.isValid()).isFalse();
  }

  @Test
  public void repeatedClose_doesNotCloseReaderOwnedByNextLease() {
    Lease first = session.borrow(16, 12, 1024);
    ImageReader reader = first.reader();
    first.markReusable();
    first.close();
    try (Lease next = session.borrow(16, 12, 1024)) {
      first.close();
      assertThat(next.reader()).isSameInstanceAs(reader);
      assertThat(reader.getSurface().isValid()).isTrue();
      assertThrows(IllegalStateException.class, first::markReusable);
      next.markReusable();
    }
  }

  @Test
  public void failedCaptureOrBudgetReduction_discardsActiveReader() {
    Surface failedSurface;
    try (Lease failed = session.borrow(16, 12, 1024)) {
      failedSurface = failed.reader().getSurface();
    }
    assertThat(failedSurface.isValid()).isFalse();

    Surface oversizedSurface;
    try (Lease active = session.borrow(16, 12, 1024)) {
      oversizedSurface = active.reader().getSurface();
      try (Lease smaller = session.borrow(8, 8, 64)) {
        smaller.markReusable();
      }
      active.markReusable();
    }
    assertThat(oversizedSurface.isValid()).isFalse();
  }

  /** Returns a reader to the idle session while retaining its identity for ownership assertions. */
  private ImageReader retain(int width, int height, long pixelLimit) {
    try (Lease lease = session.borrow(width, height, pixelLimit)) {
      lease.markReusable();
      return lease.reader();
    }
  }
}
