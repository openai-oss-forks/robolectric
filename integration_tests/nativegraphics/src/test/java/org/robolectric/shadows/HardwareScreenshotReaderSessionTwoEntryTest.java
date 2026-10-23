package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;

import android.media.ImageReader;
import android.view.Surface;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.HardwareScreenshotReaderSession.Lease;

/** Verifies two-reader native ownership, LRU replacement and aggregate active/idle accounting. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HardwareScreenshotReaderSessionTwoEntryTest {
  private final HardwareScreenshotReaderSession session = new HardwareScreenshotReaderSession();

  @After
  public void clearSession() {
    session.clear();
  }

  @Test
  public void alternatingDimensions_reuseBothReadersAtExactCombinedBudget() {
    // These readers require 768 and 1536 nominal bytes, respectively.
    ImageReader first = retain(16, 12, 2304);
    ImageReader second = retain(24, 16, 2304);

    for (int i = 0; i < 4; i++) {
      assertThat(retain(16, 12, 2304)).isSameInstanceAs(first);
      assertThat(retain(24, 16, 2304)).isSameInstanceAs(second);
    }
    assertThat(first.getSurface().isValid()).isTrue();
    assertThat(second.getSurface().isValid()).isTrue();
  }

  @Test
  public void thirdKey_evictsLeastRecentlyUsedReader() {
    ImageReader first = retain(16, 12, 4096);
    ImageReader second = retain(24, 16, 4096);
    Surface secondSurface = second.getSurface();
    assertThat(retain(16, 12, 4096)).isSameInstanceAs(first);

    ImageReader third = retain(8, 8, 4096);

    assertThat(secondSurface.isValid()).isFalse();
    assertThat(first.getSurface().isValid()).isTrue();
    assertThat(retain(8, 8, 4096)).isSameInstanceAs(third);
    assertThat(retain(16, 12, 4096)).isSameInstanceAs(first);
  }

  @Test
  public void aggregateBudget_evictsReaderWhenIndividuallyEligiblePairDoesNotFit() {
    Surface firstSurface = retain(16, 12, 2048).getSurface();
    ImageReader second = retain(24, 16, 2048);

    assertThat(firstSurface.isValid()).isFalse();
    assertThat(retain(24, 16, 2048)).isSameInstanceAs(second);
  }

  @Test
  public void overBudgetRequest_closesTemporaryAndPreservesBothEligibleReaders() {
    ImageReader first = retain(16, 12, 2304);
    ImageReader second = retain(24, 16, 2304);
    Surface temporarySurface;
    try (Lease temporary = session.borrow(32, 32, 4096, 2, 2304)) {
      temporarySurface = temporary.reader().getSurface();
      temporary.markReusable();
    }

    assertThat(temporarySurface.isValid()).isFalse();
    assertThat(retain(16, 12, 2304)).isSameInstanceAs(first);
    assertThat(retain(24, 16, 2304)).isSameInstanceAs(second);
  }

  @Test
  public void perReaderPixelLimit_stillAppliesWithAvailableAggregateBytes() {
    ImageReader first;
    try (Lease lease = session.borrow(16, 12, 256, 2, 4096)) {
      first = lease.reader();
      lease.markReusable();
    }
    Surface temporarySurface;
    try (Lease temporary = session.borrow(24, 16, 256, 2, 4096)) {
      temporarySurface = temporary.reader().getSurface();
      temporary.markReusable();
    }

    assertThat(temporarySurface.isValid()).isFalse();
    try (Lease next = session.borrow(16, 12, 256, 2, 4096)) {
      assertThat(next.reader()).isSameInstanceAs(first);
      next.markReusable();
    }
  }

  @Test
  public void nestedCapture_usesTemporaryEvenWhenItsKeyIsIdleInSecondSlot() {
    ImageReader first = retain(16, 12, 2304);
    ImageReader second = retain(24, 16, 2304);
    Surface temporarySurface;
    try (Lease outer = session.borrow(16, 12, 4096, 2, 2304)) {
      assertThat(outer.reader()).isSameInstanceAs(first);
      try (Lease nested = session.borrow(24, 16, 4096, 2, 2304)) {
        assertThat(nested.reader()).isNotSameInstanceAs(second);
        temporarySurface = nested.reader().getSurface();
        nested.markReusable();
      }
      assertThat(temporarySurface.isValid()).isFalse();
      assertThat(first.getSurface().isValid()).isTrue();
      assertThat(second.getSurface().isValid()).isTrue();
      outer.markReusable();
    }

    assertThat(retain(16, 12, 2304)).isSameInstanceAs(first);
    assertThat(retain(24, 16, 2304)).isSameInstanceAs(second);
  }

  @Test
  public void budgetReduction_countsActiveReservationWhenEvictingIdleReader() {
    ImageReader first = retain(16, 12, 2304);
    Surface secondSurface = retain(24, 16, 2304).getSurface();
    try (Lease outer = session.borrow(16, 12, 4096, 2, 2304)) {
      // The idle second reader fits 1536 bytes alone, but the active 768 bytes remain charged.
      try (Lease nested = session.borrow(8, 8, 4096, 2, 1536)) {
        nested.markReusable();
      }
      assertThat(secondSurface.isValid()).isFalse();
      assertThat(first.getSurface().isValid()).isTrue();
      outer.markReusable();
    }

    assertThat(retain(16, 12, 1536)).isSameInstanceAs(first);
  }

  @Test
  public void reset_closesIdleAndDefersActiveWithoutReplacingNewGeneration() {
    ImageReader first = retain(16, 12, 4096);
    Surface firstSurface = first.getSurface();
    Surface secondSurface = retain(24, 16, 4096).getSurface();
    ImageReader replacementA;
    ImageReader replacementB;
    try (Lease active = session.borrow(16, 12, 4096, 2, 4096)) {
      session.clear();
      assertThat(secondSurface.isValid()).isFalse();
      assertThat(firstSurface.isValid()).isTrue();
      replacementA = retain(8, 8, 4096);
      replacementB = retain(12, 12, 4096);
      active.markReusable();
    }

    assertThat(firstSurface.isValid()).isFalse();
    assertThat(retain(8, 8, 4096)).isSameInstanceAs(replacementA);
    assertThat(retain(12, 12, 4096)).isSameInstanceAs(replacementB);
    Surface replacementSurfaceA = replacementA.getSurface();
    Surface replacementSurfaceB = replacementB.getSurface();
    session.clear();
    session.clear();
    assertThat(replacementSurfaceA.isValid()).isFalse();
    assertThat(replacementSurfaceB.isValid()).isFalse();
  }

  @Test
  public void failedLease_discardsOnlyItsReaderAndRepeatedCloseLeavesNewOwnership() {
    ImageReader first = retain(16, 12, 4096);
    ImageReader second = retain(24, 16, 4096);
    Surface firstSurface = first.getSurface();
    Lease failed = session.borrow(16, 12, 4096, 2, 4096);
    failed.close();
    assertThat(firstSurface.isValid()).isFalse();
    try (Lease next = session.borrow(24, 16, 4096, 2, 4096)) {
      failed.close();
      assertThat(next.reader()).isSameInstanceAs(second);
      assertThat(second.getSurface().isValid()).isTrue();
      next.markReusable();
    }
  }

  @Test
  public void capacityOneOrInvalidCapacity_preservesOriginalPixelLimitContract() {
    Surface firstSurface = retain(16, 12, 4096).getSurface();
    ImageReader second = retain(24, 16, 4096);
    for (int capacity : new int[] {1, 0, 3, -1}) {
      try (Lease next = session.borrow(24, 16, 4096, capacity, 1)) {
        assertThat(next.reader()).isSameInstanceAs(second);
        next.markReusable();
      }
    }

    assertThat(firstSurface.isValid()).isFalse();
    assertThat(second.getSurface().isValid()).isTrue();
  }

  /** Retains a native reader while exposing its identity for resource-lifetime assertions. */
  private ImageReader retain(int width, int height, long maxBytes) {
    try (Lease lease = session.borrow(width, height, 4096, 2, maxBytes)) {
      lease.markReusable();
      return lease.reader();
    }
  }
}
