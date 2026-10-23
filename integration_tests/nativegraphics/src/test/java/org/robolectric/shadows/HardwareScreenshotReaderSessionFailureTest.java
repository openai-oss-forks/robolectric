package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.ImageReader;
import android.view.Surface;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.HardwareScreenshotReaderSession.Lease;
import org.robolectric.util.ReflectionHelpers;

/** Injects failures after native close to verify detached ownership and primary exceptions. */
@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 36,
    shadows = HardwareScreenshotReaderSessionFailureTest.FailingCloseImageReader.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HardwareScreenshotReaderSessionFailureTest {
  private final HardwareScreenshotReaderSession session = new HardwareScreenshotReaderSession();

  @After
  public void clearSession() {
    FailingCloseImageReader.failures.clear();
    session.clear();
    HardwareRenderingScreenshot.clearReaderSession();
  }

  @Test
  public void clear_closesBothReadersAndSuppressesSecondFailure() {
    ImageReader first = retain(16, 12);
    ImageReader second = retain(24, 16);
    Surface firstSurface = first.getSurface();
    Surface secondSurface = second.getSurface();
    RuntimeException firstFailure = new RuntimeException("first close");
    RuntimeException secondFailure = new RuntimeException("second close");
    FailingCloseImageReader.failures.put(first, firstFailure);
    FailingCloseImageReader.failures.put(second, secondFailure);

    RuntimeException actual = assertThrows(RuntimeException.class, session::clear);

    assertThat(actual).isSameInstanceAs(firstFailure);
    assertThat(actual.getSuppressed()).asList().containsExactly(secondFailure);
    assertThat(firstSurface.isValid()).isFalse();
    assertThat(secondSurface.isValid()).isFalse();
    ImageReader replacement = retain(16, 12);
    assertThat(retain(16, 12)).isSameInstanceAs(replacement);
  }

  @Test
  public void evictionFailure_closesAlreadyReservedMatchAndReleasesReservation() {
    ImageReader first = retain(16, 12);
    ImageReader second = retain(24, 16);
    Surface firstSurface = first.getSurface();
    Surface secondSurface = second.getSurface();
    RuntimeException evictionFailure = new RuntimeException("eviction close");
    RuntimeException reservedFailure = new RuntimeException("reserved close");
    FailingCloseImageReader.failures.put(first, evictionFailure);
    FailingCloseImageReader.failures.put(second, reservedFailure);

    // Shrinking to one entry evicts the first reader before reserving the matching second reader.
    RuntimeException actual =
        assertThrows(RuntimeException.class, () -> session.borrow(24, 16, 4096, 1, 4096));

    assertThat(actual).isSameInstanceAs(evictionFailure);
    assertThat(actual.getSuppressed()).asList().containsExactly(reservedFailure);
    assertThat(firstSurface.isValid()).isFalse();
    assertThat(secondSurface.isValid()).isFalse();
    ImageReader replacement = retain(24, 16);
    assertThat(retain(24, 16)).isSameInstanceAs(replacement);
  }

  @Test
  public void sameCleanupFailure_avoidsSelfSuppressionAndLeavesSessionEmpty() {
    ImageReader first = retain(16, 12);
    ImageReader second = retain(24, 16);
    Surface firstSurface = first.getSurface();
    Surface secondSurface = second.getSurface();
    RuntimeException failure = new RuntimeException("shared close failure");
    FailingCloseImageReader.failures.put(first, failure);
    FailingCloseImageReader.failures.put(second, failure);

    assertThat(assertThrows(RuntimeException.class, session::clear)).isSameInstanceAs(failure);

    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(firstSurface.isValid()).isFalse();
    assertThat(secondSurface.isValid()).isFalse();
    session.clear();
  }

  @Test
  public void clear_errorPrimaryStillClosesSecondReader() {
    ImageReader first = retain(16, 12);
    ImageReader second = retain(24, 16);
    Surface firstSurface = first.getSurface();
    Surface secondSurface = second.getSurface();
    AssertionError firstFailure = new AssertionError("first close");
    RuntimeException secondFailure = new RuntimeException("second close");
    FailingCloseImageReader.failures.put(first, firstFailure);
    FailingCloseImageReader.failures.put(second, secondFailure);

    AssertionError actual = assertThrows(AssertionError.class, session::clear);

    assertThat(actual).isSameInstanceAs(firstFailure);
    assertThat(actual.getSuppressed()).asList().containsExactly(secondFailure);
    assertThat(firstSurface.isValid()).isFalse();
    assertThat(secondSurface.isValid()).isFalse();
  }

  @Test
  public void resetHook_cleanupFailurePropagatesAfterBothReadersClose() {
    HardwareScreenshotReaderSession sandboxSession =
        ReflectionHelpers.getStaticField(HardwareRenderingScreenshot.class, "readerSession");
    ImageReader first;
    ImageReader second;
    try (Lease lease = sandboxSession.borrow(16, 12, 4096, 2, 4096)) {
      first = lease.reader();
      lease.markReusable();
    }
    try (Lease lease = sandboxSession.borrow(24, 16, 4096, 2, 4096)) {
      second = lease.reader();
      lease.markReusable();
    }
    Surface firstSurface = first.getSurface();
    Surface secondSurface = second.getSurface();
    RuntimeException failure = new RuntimeException("reset close");
    FailingCloseImageReader.failures.put(first, failure);

    assertThat(assertThrows(RuntimeException.class, ShadowViewRootImpl::reset))
        .isSameInstanceAs(failure);

    assertThat(firstSurface.isValid()).isFalse();
    assertThat(secondSurface.isValid()).isFalse();
    ShadowViewRootImpl.reset();
  }

  private ImageReader retain(int width, int height) {
    try (Lease lease = session.borrow(width, height, 4096, 2, 4096)) {
      lease.markReusable();
      return lease.reader();
    }
  }

  /** Preserves the native close operation and throws a configured failure once afterward. */
  @Implements(value = ImageReader.class, callNativeMethodsByDefault = true)
  public static class FailingCloseImageReader extends ShadowNativeImageReader {
    static final Map<ImageReader, Throwable> failures = new IdentityHashMap<>();

    @Implementation
    protected void close() {
      // Surface.release can reenter close; remove the fixture before invoking platform cleanup.
      Throwable failure = failures.remove(realImageReader);
      Shadow.directlyOn(realImageReader, ImageReader.class, "close");
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      } else if (failure != null) {
        throw (Error) failure;
      }
    }
  }
}
