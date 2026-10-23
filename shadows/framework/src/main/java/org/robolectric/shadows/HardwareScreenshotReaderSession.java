package org.robolectric.shadows;

import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * Retains at most two screenshot readers in the current SDK sandbox generation. One capture at a
 * time can lease retained ownership; nested captures and captures outside the retention budget own
 * temporary readers.
 *
 * <p>All readers use the same RGBA format, default usage and single-image limit. Only dimensions
 * vary in the key. The byte budget charges one nominal RGBA plane per retained reader, including
 * its active lease. Native buffers can exceed this nominal size. Temporary readers and active
 * leases invalidated by reset close on return and are outside the retention budget. Creation and
 * close run outside the state lock.
 */
final class HardwareScreenshotReaderSession {
  // Least recently used first. The active reservation remains charged after removal from this list.
  private final List<ImageReader> idle = new ArrayList<>(2);
  // A negative reservation means no retained reader is in use.
  private long activeBytes = -1;
  private long generation;
  private long pixelLimit;
  private long byteLimit;
  private int capacity;

  /** Borrows a reader with a one-size cache. */
  Lease borrow(int width, int height, long maxRetainedPixels) {
    return borrow(width, height, maxRetainedPixels, 1, Long.MAX_VALUE);
  }

  /**
   * Borrows an exclusive reader. Only the SDK main thread can retain readers between captures. Only
   * a two-size cache needs a separate byte limit across the two readers.
   */
  Lease borrow(
      int width, int height, long maxRetainedPixels, int requestedCapacity, long maxRetainedBytes) {
    ImageReader reader = null;
    List<ImageReader> evicted = null;
    boolean retain = false;
    long leaseGeneration;
    long pixels = (long) width * height;
    long bytes = nominalBytes(width, height);
    synchronized (this) {
      leaseGeneration = generation;
      if (Looper.myLooper() == Looper.getMainLooper()) {
        pixelLimit = Math.max(0, maxRetainedPixels);
        capacity = requestedCapacity == 2 ? 2 : 1;
        byteLimit = capacity == 2 ? Math.max(0, maxRetainedBytes) : Long.MAX_VALUE;
        for (int i = idle.size() - 1; i >= 0; i--) {
          if (pixels(idle.get(i)) > pixelLimit) {
            evicted = evict(i, evicted);
          }
        }
        while (!idle.isEmpty()
            && (idle.size() + (activeBytes >= 0 ? 1 : 0) > capacity
                || idleBytes() > byteLimit - Math.max(0, activeBytes))) {
          evicted = evict(0, evicted);
        }

        if (activeBytes < 0 && bytes >= 0 && pixels <= pixelLimit && bytes <= byteLimit) {
          for (int i = 0; i < idle.size(); i++) {
            ImageReader candidate = idle.get(i);
            if (candidate.getWidth() == width && candidate.getHeight() == height) {
              reader = idle.remove(i);
              break;
            }
          }
          while (!idle.isEmpty() && (idle.size() >= capacity || idleBytes() > byteLimit - bytes)) {
            evicted = evict(0, evicted);
          }
          activeBytes = bytes;
          retain = true;
        }
      }
    }

    try {
      closeReaders(evicted);
      if (reader == null) {
        // Preserve ImageReader's platform default usage for this SDK sandbox.
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1);
      }
      return new Lease(this, reader, retain, leaseGeneration);
    } catch (RuntimeException | Error failure) {
      synchronized (this) {
        if (retain && leaseGeneration == generation) {
          activeBytes = -1;
        }
      }
      // A matched reader may already be reserved when another entry's eviction fails.
      if (reader != null) {
        try {
          reader.close();
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
      }
      throw failure;
    }
  }

  /** Invalidates active leases and closes idle ownership without interrupting a current capture. */
  void clear() {
    List<ImageReader> evicted;
    synchronized (this) {
      generation++;
      activeBytes = -1;
      evicted = idle.isEmpty() ? null : new ArrayList<>(idle);
      idle.clear();
    }
    closeReaders(evicted);
  }

  private void release(Lease lease, boolean reusable) {
    boolean retained = false;
    synchronized (this) {
      if (lease.retain && lease.generation == generation) {
        long bytes = activeBytes;
        activeBytes = -1;
        if (reusable
            && pixels(lease.reader) <= pixelLimit
            && bytes <= byteLimit
            && idle.size() < capacity
            && idleBytes() <= byteLimit - bytes) {
          idle.add(lease.reader);
          retained = true;
        }
      }
    }
    if (!retained) {
      lease.reader.close();
    }
  }

  /** Removes ownership under the session lock; the caller closes the collected readers later. */
  private List<ImageReader> evict(int index, List<ImageReader> evicted) {
    if (evicted == null) {
      evicted = new ArrayList<>(2);
    }
    evicted.add(idle.remove(index));
    return evicted;
  }

  /** Closes every detached reader and preserves the first failure when several closes fail. */
  private static void closeReaders(List<ImageReader> readers) {
    if (readers == null) {
      return;
    }
    Throwable failure = null;
    for (ImageReader reader : readers) {
      try {
        reader.close();
      } catch (RuntimeException | Error cleanupFailure) {
        if (failure == null) {
          failure = cleanupFailure;
        } else if (cleanupFailure != failure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    } else if (failure != null) {
      throw (Error) failure;
    }
  }

  /** An unrepresentable or invalid size uses the uncached constructor and its normal validation. */
  private static long nominalBytes(int width, int height) {
    long pixels = (long) width * height;
    if (width <= 0 || height <= 0 || pixels > Long.MAX_VALUE / 4) {
      return -1;
    }
    return pixels * 4;
  }

  private static long pixels(ImageReader reader) {
    return (long) reader.getWidth() * reader.getHeight();
  }

  /** At most two readers are idle. Sum their sizes under the session lock. */
  private long idleBytes() {
    long bytes = 0;
    for (int i = 0; i < idle.size(); i++) {
      bytes += pixels(idle.get(i)) * 4;
    }
    return bytes;
  }

  /**
   * Releases exactly once. A capture must mark success after its Image closes and its renderer
   * detaches; otherwise close discards the reader.
   */
  static final class Lease implements AutoCloseable {
    private final HardwareScreenshotReaderSession session;
    private final ImageReader reader;
    private final boolean retain;
    private final long generation;
    private boolean reusable;
    private boolean closed;

    private Lease(
        HardwareScreenshotReaderSession session,
        ImageReader reader,
        boolean retain,
        long generation) {
      this.session = session;
      this.reader = reader;
      this.retain = retain;
      this.generation = generation;
    }

    ImageReader reader() {
      return reader;
    }

    synchronized void markReusable() {
      if (closed) {
        throw new IllegalStateException("Screenshot reader lease is closed");
      }
      reusable = true;
    }

    @Override
    public void close() {
      boolean succeeded;
      synchronized (this) {
        if (closed) {
          return;
        }
        closed = true;
        succeeded = reusable;
      }
      session.release(this, succeeded);
    }
  }
}
