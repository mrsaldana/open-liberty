/*******************************************************************************
 * Copyright (c) 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.openliberty.http.netty.timeout;

import com.ibm.ws.http.netty.NettyHttpChannelConfig;

import io.openliberty.http.netty.timeout.exception.PersistTimeoutException;
import io.openliberty.http.netty.timeout.exception.ReadTimeoutException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.ScheduledFuture;

public class TimeoutHandler extends ChannelDuplexHandler {

    private static final TimeUnit LEGACY_UNIT = TimeUnit.MILLISECONDS;

    private final long readTimeout;
    private final long persistTimeout;

    private volatile TimeoutType phase = TimeoutType.READ;
    private volatile ChannelHandlerContext context;

    private final AtomicReference<ScheduledFuture<?>> currentTimeout = new AtomicReference<>();

    private final Runnable timerTask = () -> timeoutFired();

    public TimeoutHandler(NettyHttpChannelConfig config) {
        this.readTimeout = config.getReadTimeout();
        this.persistTimeout = config.getPersistTimeout();
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        this.context = context;
        this.phase = TimeoutType.READ;
        activateTimer();
        super.channelActive(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        cancelTimer();
        super.channelInactive(context);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof FullHttpRequest) {
            cancelTimer();
            phase = TimeoutType.READ;
        }
        super.channelRead(context, message);
    }

    /**
     * Capture outbound writes. Once the server writes a FullHttpResponse,
     * we assume we've finished handling the request. At that point,
     * let's switch to "persist" mode
     * 
     * NOTE: Technically, persist should be for only the first read of the next request.
     * But the way we currently operate, and with autoread enabled, requests are
     * aggregated and queued up prior to this point. So this only provides partial
     * coverage of the legacy implementation. A more loyal implementation can be provided
     * by disabling auto-read which will be tackled at a later point.
     */
    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof FullHttpResponse || message instanceof LastHttpContent) {
            promise.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    cancelTimer();
                    phase = TimeoutType.PERSIST;
                    activateTimer();
                }
            });
        }
        super.write(context, message, promise);
    }

    private void activateTimer() {

        long timeout = (phase == TimeoutType.READ) ? readTimeout : persistTimeout;

        if (context == null || timeout <= 0) {
            return;
        }

        cancelTimer();
        ScheduledFuture<?> future = context.executor().schedule(timerTask, timeout, LEGACY_UNIT);
        currentTimeout.set(future);
    }

    private void timeoutFired() {
        IOException exception = (phase == TimeoutType.READ) ? new ReadTimeoutException(readTimeout, LEGACY_UNIT) : new PersistTimeoutException(persistTimeout, LEGACY_UNIT);
        context.fireExceptionCaught(exception);
    }

    private void cancelTimer() {
        ScheduledFuture<?> current = currentTimeout.getAndSet(null);
        if (current != null) {
            current.cancel(false); //Do not interrupt thread
        }
    }
}