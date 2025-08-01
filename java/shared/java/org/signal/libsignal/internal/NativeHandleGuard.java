//
// Copyright 2021 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.internal;

import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/**
 * Provides access to a Rust object handle while keeping the Java wrapper alive.
 *
 * <p>Intended for use with try-with-resources syntax. NativeHandleGuard prevents the Java wrapper
 * from being finalized, which would destroy the Rust object, while the handle is in use. To use it,
 * the Java wrapper type should conform to the {@link NativeHandleGuard.Owner} interface.
 *
 * <p>Note that it is not necessary to use NativeHandleGuard in the implementation of {@code
 * finalize} itself. The point of NativeHandleGuard is to delay finalization while the Rust object
 * is being used; once finalization has begun, there can be no other uses of the Rust object from
 * Java.
 */
public class NativeHandleGuard implements AutoCloseable {
  /**
   * @see NativeHandleGuard
   */
  public interface Owner {
    long unsafeNativeHandleWithoutGuard();

    default NativeHandleGuard guard() {
      return new NativeHandleGuard(this);
    }

    default <T> T guardedMap(final LongFunction<T> function) {
      try (final NativeHandleGuard guard = new NativeHandleGuard(this)) {
        return function.apply(guard.nativeHandle());
      }
    }

    default <T> T guardedMapChecked(final FilterExceptions.ThrowingLongFunction<T> function)
        throws Exception {
      try (final NativeHandleGuard guard = new NativeHandleGuard(this)) {
        return function.apply(guard.nativeHandle());
      }
    }

    default void guardedRun(final LongConsumer consumer) {
      try (final NativeHandleGuard guard = new NativeHandleGuard(this)) {
        consumer.accept(guard.nativeHandle());
      }
    }

    default void guardedRunChecked(final FilterExceptions.ThrowingLongConsumer consumer)
        throws Exception {
      try (final NativeHandleGuard guard = new NativeHandleGuard(this)) {
        consumer.accept(guard.nativeHandle());
      }
    }
  }

  public abstract static class SimpleOwner implements Owner {

    private final long nativeHandle;

    protected SimpleOwner(final long nativeHandle) {
      this.nativeHandle = nativeHandle;
    }

    protected abstract void release(long nativeHandle);

    protected static final long throwIfNull(long handle) {
      if (handle == 0L) {
        throw new NullPointerException();
      }
      return handle;
    }

    @Override
    @CalledFromNative
    public long unsafeNativeHandleWithoutGuard() {
      return nativeHandle;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() {
      release(this.nativeHandle);
    }
  }

  // A note on synchronization.
  //
  // close is synchronized to eliminate the race between it and finalize.
  //
  // All in the name of calling release exactly once.
  public abstract static class CloseableOwner extends SimpleOwner implements AutoCloseable {
    private boolean isClosed = false;

    protected CloseableOwner(long nativeHandle) {
      super(nativeHandle);
    }

    @Override
    public synchronized void close() {
      if (isClosed) {
        return;
      }
      this.isClosed = true;
      this.release(this.unsafeNativeHandleWithoutGuard());
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() {
      close();
      // Not calling super.finalize() is fine, because close already does the same thing.
    }
  }

  private final Owner owner;

  public NativeHandleGuard(Owner owner) {
    this.owner = owner;
  }

  /** Returns the native handle owned by the Java object, or 0 if the owner is {@code null}. */
  public long nativeHandle() {
    if (owner == null) {
      return 0;
    }
    return owner.unsafeNativeHandleWithoutGuard();
  }

  public void close() {
    // Act as an optimization barrier, so the whole guard doesn't get inlined away.
    // (In Java 9 we'd use Reference.reachabilityFence() for the same effect.)
    Native.keepAlive(this.owner);
  }
}
