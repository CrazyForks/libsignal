//
// Copyright 2025 Signal Messenger, LLC.
// SPDX-License-Identifier: AGPL-3.0-only
//

package org.signal.libsignal.net;

import org.signal.libsignal.internal.CompletableFuture;
import org.signal.libsignal.internal.Native;
import org.signal.libsignal.net.internal.BridgeChatListener;

/**
 * Represents an unauthenticated (i.e. hopefully anonymous) communication channel with the
 * ChatService.
 *
 * <p>Created by the factory method {@link Network#connectUnauthChat} rather than instantiated
 * directly.
 *
 * <p>Note that a newly-created instance of this class won't be usable for sending messages or
 * receiving events until {@link ChatConnection#start()} is called.
 */
public class UnauthenticatedChatConnection extends ChatConnection {
  private UnauthenticatedChatConnection(
      final TokioAsyncContext tokioAsyncContext,
      long nativeHandle,
      ChatConnectionListener listener) {
    super(tokioAsyncContext, nativeHandle, listener);
  }

  static CompletableFuture<UnauthenticatedChatConnection> connect(
      final TokioAsyncContext tokioAsyncContext,
      final Network.ConnectionManager connectionManager,
      ChatConnectionListener chatListener) {
    return tokioAsyncContext.guardedMap(
        asyncContextHandle ->
            connectionManager.guardedMap(
                connectionManagerHandle ->
                    Native.UnauthenticatedChatConnection_connect(
                            asyncContextHandle, connectionManagerHandle)
                        .thenApply(
                            nativeHandle ->
                                new UnauthenticatedChatConnection(
                                    tokioAsyncContext, nativeHandle, chatListener))));
  }

  // Implementing these abstract methods from ChatConnection allows UnauthenticatedChatConnection
  //   to get the implementation of its main functionality (connect, send, etc.)
  //   using the shared implementations of those methods in ChatConnection.
  @Override
  protected CompletableFuture disconnectWrapper(
      long nativeAsyncContextHandle, long nativeChatServiceHandle) {
    return Native.UnauthenticatedChatConnection_disconnect(
        nativeAsyncContextHandle, nativeChatServiceHandle);
  }

  @Override
  protected void startWrapper(long nativeChatConnectionHandle, BridgeChatListener listener) {
    Native.UnauthenticatedChatConnection_init_listener(nativeChatConnectionHandle, listener);
  }

  @Override
  protected CompletableFuture<Object> sendWrapper(
      long nativeAsyncContextHandle,
      long nativeChatServiceHandle,
      long nativeRequestHandle,
      int timeoutMillis) {
    return Native.UnauthenticatedChatConnection_send(
        nativeAsyncContextHandle, nativeChatServiceHandle, nativeRequestHandle, timeoutMillis);
  }

  @Override
  protected void release(long nativeChatServiceHandle) {
    Native.UnauthenticatedChatConnection_Destroy(nativeChatServiceHandle);
  }
}
