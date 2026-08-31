/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.http.netty.pipeline.http2;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2LifecycleManager;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Stream;

/**
 * Connection-scoped accounting policy for DATA retained by Netty's remote flow
 * controller.
 */
final class LibertyMaxQueuedBytesFlowController implements Http2RemoteFlowController, Http2LifecycleManager {

    private static final String LIMIT_MESSAGE = "Total queued bytes across all streams exceeded limit!";

    interface Clock {
        long now();
    }

    interface Cancellable {
        void cancel();
    }

    interface Scheduler {
        Cancellable schedule(Runnable task, long delayMillis);
    }

    private enum State {
        LIVE,
        RETAINED_TIMEOUT,
        MERGED_AWAY,
        COMPLETED,
        ERRORED,
        CONNECTION_FORCED
    }

    private enum PublicationState {
        RESERVED,
        DELEGATING,
        DELEGATE_OWNED
    }

    private final Http2Connection connection;
    private final Http2RemoteFlowController delegate;
    private final long maxQueuedBytes;
    private final long writeTimeout;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Map<Integer, StreamLedger> streamLedgers = new HashMap<Integer, StreamLedger>();
    private final Http2Exception limitCause = new Http2Exception(Http2Error.ENHANCE_YOUR_CALM, LIMIT_MESSAGE,
                    Http2Exception.ShutdownHint.GRACEFUL_SHUTDOWN);

    private Http2LifecycleManager lifecycleDelegate;
    private Http2ConnectionEncoder encoder;
    private ChannelHandlerContext context;
    private long liveBytes;
    private long retainedBytes;
    private boolean limitForwarded;
    private boolean pendingLimitForward;
    private boolean closeListenerRegistered;
    private boolean connectionClosed;
    private boolean failClosedCloseRequested;
    private boolean failClosedCloseAttempted;
    private Throwable failClosedCause;

    LibertyMaxQueuedBytesFlowController(Http2Connection connection, Http2RemoteFlowController delegate,
                                        long maxQueuedBytes, long writeTimeout) {
        this(connection, delegate, maxQueuedBytes, writeTimeout, new Clock() {
            @Override
            public long now() {
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            }
        }, null);
    }

    LibertyMaxQueuedBytesFlowController(Http2Connection connection, Http2RemoteFlowController delegate,
                                        long maxQueuedBytes, long writeTimeout, Clock clock, Scheduler scheduler) {
        if (connection == null || delegate == null || clock == null) {
            throw new NullPointerException();
        }
        this.connection = connection;
        this.delegate = delegate;
        this.maxQueuedBytes = maxQueuedBytes;
        this.writeTimeout = writeTimeout;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    void lifecycleManager(Http2LifecycleManager lifecycleManager) {
        Http2ConnectionEncoder publicEncoder = null;
        synchronized (this) {
            lifecycleDelegate = lifecycleManager;
            if (lifecycleManager instanceof Http2ConnectionHandler) {
                encoder = ((Http2ConnectionHandler) lifecycleManager).encoder();
                publicEncoder = encoder;
            }
        }
        if (publicEncoder != null) {
            publicEncoder.lifecycleManager(this);
        }
    }

    synchronized long liveBytes() {
        return liveBytes;
    }

    synchronized long retainedBytes() {
        return retainedBytes;
    }

    synchronized int activeStreamLedgerCount() {
        return streamLedgers.size();
    }

    long maxQueuedBytes() {
        return maxQueuedBytes;
    }

    long writeTimeout() {
        return writeTimeout;
    }

    @Override
    public void addFlowControlled(Http2Stream stream, FlowControlled payload) {
        if (maxQueuedBytes <= 0) {
            delegate.addFlowControlled(stream, payload);
            return;
        }

        final int payloadSize = payload.size();
        if (payloadSize < 0) {
            throw new IllegalStateException("negative flow-controlled size: " + payloadSize);
        }
        if (payloadSize == 0) {
            delegate.addFlowControlled(stream, payload);
            return;
        }

        AccountingFlowControlled accounting = null;
        StreamLedger admittedLedger = null;
        ChannelHandlerContext errorContext = null;
        boolean rejected = false;
        synchronized (this) {
            requireOpen();
            long total = checkedAdd(liveBytes, retainedBytes, "queued-byte total overflow");
            if (total < 0 || total > maxQueuedBytes || payloadSize > maxQueuedBytes - total) {
                rejected = true;
                errorContext = context;
            } else {
                liveBytes = checkedAdd(liveBytes, payloadSize, "live-byte overflow");
                StreamLedger ledger = ledger(stream);
                long generation = nextGeneration(ledger);
                long deadline = writeTimeout > 0 ? clock.now() + writeTimeout : 0;
                accounting = new AccountingFlowControlled(stream, payload, payloadSize, generation, deadline);
                ledger.wrappers.add(accounting);
                admittedLedger = ledger;
            }
        }

        if (rejected) {
            payload.error(errorContext, limitCause);
            return;
        }

        Throwable timerFailure = reconcileTimer(admittedLedger);
        if (timerFailure != null) {
            terminateAdmission(accounting, timerFailure);
            throwUnchecked(timerFailure);
        }

        synchronized (this) {
            accounting.publicationState = PublicationState.DELEGATING;
        }
        try {
            delegate.addFlowControlled(stream, accounting);
            boolean timeoutDue;
            synchronized (this) {
                accounting.publicationState = PublicationState.DELEGATE_OWNED;
                timeoutDue = accounting.timeoutDue;
                accounting.timeoutDue = false;
            }
            if (timeoutDue) {
                onHeadTimeout(stream, accounting, accounting.generation, accounting.originalDeadline);
            }
        } catch (Throwable failure) {
            try {
                accounting.error(context, failure);
            } catch (Throwable cleanupFailure) {
                addSuppressed(failure, cleanupFailure);
            }
            throwUnchecked(failure);
        }
    }

    @Override
    public ChannelHandlerContext channelHandlerContext() {
        return delegate.channelHandlerContext();
    }

    @Override
    public void channelHandlerContext(final ChannelHandlerContext ctx) throws Http2Exception {
        boolean registerCloseListener;
        synchronized (this) {
            context = ctx;
            registerCloseListener = !closeListenerRegistered;
            closeListenerRegistered = true;
        }

        if (registerCloseListener) {
            try {
                ctx.channel().closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        Throwable closeCause;
                        synchronized (LibertyMaxQueuedBytesFlowController.this) {
                            closeCause = failClosedCause;
                        }
                        try {
                            onConnectionClosed(closeCause == null ? new ClosedChannelException() : closeCause);
                        } catch (Throwable cleanupFailure) {
                            ctx.fireExceptionCaught(cleanupFailure);
                        }
                    }
                });
            } catch (Throwable registrationFailure) {
                try {
                    onConnectionClosed(registrationFailure);
                } catch (Throwable cleanupFailure) {
                    addSuppressed(registrationFailure, cleanupFailure);
                }
                throwUnchecked(registrationFailure);
            }
        }


        Http2ConnectionEncoder publicEncoder;
        synchronized (this) {
            publicEncoder = encoder;
        }
        if (publicEncoder != null) {
            publicEncoder.lifecycleManager(this);
        }
        attemptFailClosedConnectionClose();
        synchronized (this) {
            if (connectionClosed) {
                return;
            }
        }

        adjudicateBeforeDelegateBind();
        forwardPendingLimit(ctx);
        delegate.channelHandlerContext(ctx);
    }

    @Override
    public void initialWindowSize(int value) throws Http2Exception {
        delegate.initialWindowSize(value);
    }

    @Override
    public int initialWindowSize() {
        return delegate.initialWindowSize();
    }

    @Override
    public int windowSize(Http2Stream stream) {
        return delegate.windowSize(stream);
    }

    @Override
    public void incrementWindowSize(Http2Stream stream, int delta) throws Http2Exception {
        delegate.incrementWindowSize(stream, delta);
    }

    @Override
    public boolean hasFlowControlled(Http2Stream stream) {
        return delegate.hasFlowControlled(stream);
    }

    @Override
    public void writePendingBytes() throws Http2Exception {
        delegate.writePendingBytes();
    }

    @Override
    public void listener(Listener listener) {
        delegate.listener(listener);
    }

    @Override
    public boolean isWritable(Http2Stream stream) {
        return delegate.isWritable(stream);
    }

    @Override
    public void channelWritabilityChanged() throws Http2Exception {
        delegate.channelWritabilityChanged();
    }

    @Override
    public void updateDependencyTree(int childStreamId, int parentStreamId, short weight, boolean exclusive) {
        delegate.updateDependencyTree(childStreamId, parentStreamId, weight, exclusive);
    }

    @Override
    public void closeStreamLocal(Http2Stream stream, ChannelFuture future) {
        target().closeStreamLocal(stream, future);
    }

    @Override
    public void closeStreamRemote(Http2Stream stream, ChannelFuture future) {
        target().closeStreamRemote(stream, future);
    }

    @Override
    public void closeStream(Http2Stream stream, ChannelFuture future) {
        target().closeStream(stream, future);
    }

    @Override
    public ChannelFuture resetStream(ChannelHandlerContext ctx, int streamId, long errorCode,
                                     ChannelPromise promise) {
        return target().resetStream(ctx, streamId, errorCode, promise);
    }

    @Override
    public ChannelFuture goAway(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData,
                                ChannelPromise promise) {
        return target().goAway(ctx, lastStreamId, errorCode, debugData, promise);
    }

    @Override
    public void onError(ChannelHandlerContext ctx, boolean outbound, Throwable cause) {
        Http2LifecycleManager target;
        synchronized (this) {
            target = lifecycleDelegate;
            if (cause == limitCause) {
                if (limitForwarded) {
                    return;
                }
                if (target == null || context == null) {
                    pendingLimitForward = true;
                    return;
                }
                limitForwarded = true;
            }
        }
        if (target != null) {
            try {
                target.onError(ctx, outbound, cause);
            } catch (Throwable failure) {
                requestFailClosedConnectionClose(failure);
                throwUnchecked(failure);
            }
        }
    }

    private synchronized Http2LifecycleManager target() {
        if (lifecycleDelegate == null) {
            throw new IllegalStateException("HTTP/2 lifecycle manager is not bound");
        }
        return lifecycleDelegate;
    }

    private void requireOpen() {
        if (connectionClosed) {
            throw new IllegalStateException("HTTP/2 connection is closed");
        }
        if (liveBytes < 0 || retainedBytes < 0) {
            throw new IllegalStateException("negative queued-byte ledger");
        }
    }

    private StreamLedger ledger(Http2Stream stream) {
        StreamLedger ledger = streamLedgers.get(stream.id());
        if (ledger == null) {
            ledger = new StreamLedger(stream);
            streamLedgers.put(stream.id(), ledger);
        }
        return ledger;
    }

    private long nextGeneration(StreamLedger ledger) {
        ledger.nextGeneration++;
        if (ledger.nextGeneration == 0) {
            ledger.nextGeneration++;
        }
        return ledger.nextGeneration;
    }

    private static long checkedAdd(long left, long right, String message) {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            throw new IllegalStateException(message);
        }
        return left + right;
    }

    private void debitLive(long bytes) {
        if (bytes < 0 || bytes > liveBytes) {
            throw new IllegalStateException("live-byte underflow");
        }
        liveBytes -= bytes;
    }

    private void addRetained(long bytes) {
        retainedBytes = checkedAdd(retainedBytes, bytes, "retained-byte overflow");
    }

    private void debitRetained(long bytes) {
        if (bytes < 0 || bytes > retainedBytes) {
            throw new IllegalStateException("retained-byte underflow");
        }
        retainedBytes -= bytes;
    }

    private StreamLedger removeWrapperLocked(AccountingFlowControlled wrapper) {
        StreamLedger ledger = streamLedgers.get(wrapper.stream.id());
        if (ledger != null) {
            ledger.wrappers.remove(wrapper);
        }
        return ledger;
    }

    private AccountingFlowControlled liveHead(StreamLedger ledger) {
        for (AccountingFlowControlled candidate : ledger.wrappers) {
            if (candidate.state == State.LIVE && candidate.remaining > 0) {
                return candidate;
            }
        }
        return null;
    }

    private Cancellable detachTimerLocked(StreamLedger ledger) {
        Cancellable timer = ledger.timer;
        ledger.timer = null;
        ledger.timerEpoch++;
        ledger.armedHead = null;
        ledger.armedGeneration = 0;
        ledger.armedDeadline = 0;
        return timer;
    }

    private Throwable reconcileTimer(final StreamLedger ledger) {
        Cancellable detached = null;
        TimerSchedule schedule = null;
        synchronized (this) {
            AccountingFlowControlled head = streamLedgers.get(ledger.stream.id()) == ledger && !connectionClosed
                            ? liveHead(ledger) : null;
            if (head != null && writeTimeout > 0 && ledger.armedHead == head && ledger.timer != null) {
                return null;
            }

            detached = detachTimerLocked(ledger);
            if (head == null || writeTimeout <= 0 || scheduler == null && context == null) {
                if (ledger.wrappers.isEmpty()) {
                    streamLedgers.remove(ledger.stream.id(), ledger);
                }
            } else {
                ledger.armedHead = head;
                ledger.armedGeneration = head.generation;
                ledger.armedDeadline = head.originalDeadline;
                long epoch = ledger.timerEpoch;
                long delay = head.originalDeadline - clock.now();
                schedule = new TimerSchedule(ledger, head, head.generation, head.originalDeadline,
                                epoch, delay < 0 ? 0 : delay, context);
            }
        }

        Throwable primary = cancel(detached, null);
        if (schedule == null) {
            return primary;
        }

        Cancellable scheduled = null;
        Throwable scheduleFailure = null;
        try {
            scheduled = schedule(schedule);
        } catch (Throwable failure) {
            scheduleFailure = failure;
        }

        boolean accepted;
        synchronized (this) {
            accepted = scheduleFailure == null && !connectionClosed
                            && streamLedgers.get(ledger.stream.id()) == ledger
                            && ledger.timerEpoch == schedule.epoch && ledger.armedHead == schedule.head
                            && ledger.armedGeneration == schedule.generation
                            && ledger.armedDeadline == schedule.deadline;
            if (accepted) {
                ledger.timer = scheduled;
            } else if (ledger.timerEpoch == schedule.epoch) {
                ledger.timerEpoch++;
                ledger.armedHead = null;
                ledger.armedGeneration = 0;
                ledger.armedDeadline = 0;
                if (ledger.wrappers.isEmpty()) {
                    streamLedgers.remove(ledger.stream.id(), ledger);
                }
            }
        }

        if (!accepted) {
            primary = cancel(scheduled, primary);
        }
        if (scheduleFailure != null) {
            primary = combine(primary, scheduleFailure);
        }
        return primary;
    }

    private Cancellable schedule(final TimerSchedule schedule) {
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                onHeadTimeout(schedule.ledger.stream, schedule.head, schedule.generation, schedule.deadline);
            }
        };
        if (scheduler != null) {
            return scheduler.schedule(task, schedule.delay);
        }
        final ScheduledFuture<?> future = schedule.context.executor().schedule(task, schedule.delay,
                        TimeUnit.MILLISECONDS);
        return new Cancellable() {
            @Override
            public void cancel() {
                future.cancel(false);
            }
        };
    }

    private static Throwable cancel(Cancellable cancellable, Throwable primary) {
        if (cancellable != null) {
            try {
                cancellable.cancel();
            } catch (Throwable failure) {
                primary = combine(primary, failure);
            }
        }
        return primary;
    }

    private void terminateAdmission(AccountingFlowControlled wrapper, Throwable primary) {
        StreamLedger ledger;
        synchronized (this) {
            if (wrapper.callbackInvoked) {
                return;
            }
            wrapper.callbackInvoked = true;
            if (wrapper.state == State.LIVE) {
                debitLive(wrapper.remaining);
            }
            wrapper.state = State.ERRORED;
            wrapper.remaining = 0;
            ledger = removeWrapperLocked(wrapper);
        }
        if (ledger != null) {
            primary = combine(primary, reconcileTimer(ledger));
        }
        try {
            wrapper.payload.error(context, primary);
        } catch (Throwable callbackFailure) {
            addSuppressed(primary, callbackFailure);
        }
    }

    private void adjudicateBeforeDelegateBind() {
        List<StreamLedger> ledgers;
        synchronized (this) {
            ledgers = new ArrayList<StreamLedger>(streamLedgers.values());
        }
        Throwable primary = null;
        for (StreamLedger ledger : ledgers) {
            AccountingFlowControlled head;
            boolean pendingReset;
            synchronized (this) {
                head = streamLedgers.get(ledger.stream.id()) == ledger ? liveHead(ledger) : null;
                pendingReset = ledger.pendingReset;
            }
            try {
                if (pendingReset) {
                    performPendingReset(ledger);
                } else if (head != null && clock.now() - head.originalDeadline >= 0) {
                    onHeadTimeout(ledger.stream, head, head.generation, head.originalDeadline);
                } else {
                    Throwable timerFailure = reconcileTimer(ledger);
                    if (timerFailure != null) {
                        throwUnchecked(timerFailure);
                    }
                }
            } catch (Throwable failure) {
                primary = combine(primary, failure);
            }
        }
        if (primary != null) {
            throwUnchecked(primary);
        }
    }

    private void forwardPendingLimit(ChannelHandlerContext ctx) {
        Http2LifecycleManager target = null;
        synchronized (this) {
            if (pendingLimitForward && !limitForwarded && lifecycleDelegate != null) {
                pendingLimitForward = false;
                limitForwarded = true;
                target = lifecycleDelegate;
            }
        }
        if (target != null) {
            try {
                target.onError(ctx, true, limitCause);
            } catch (Throwable failure) {
                requestFailClosedConnectionClose(failure);
                throwUnchecked(failure);
            }
        }
    }

    private void onHeadTimeout(Http2Stream stream, AccountingFlowControlled head, long generation, long deadline) {
        StreamLedger ledger;
        Cancellable timer;
        boolean early;
        synchronized (this) {
            ledger = streamLedgers.get(stream.id());
            if (connectionClosed || ledger == null || ledger.armedHead != head
                            || ledger.armedGeneration != generation || ledger.armedDeadline != deadline
                            || head.state != State.LIVE) {
                return;
            }
            if (head.publicationState != PublicationState.DELEGATE_OWNED) {
                head.timeoutDue = true;
                return;
            }
            early = clock.now() - deadline < 0;
            timer = detachTimerLocked(ledger);
            if (!early) {
                for (AccountingFlowControlled wrapper : ledger.wrappers) {
                    if (wrapper.state == State.LIVE) {
                        debitLive(wrapper.remaining);
                        addRetained(wrapper.remaining);
                        wrapper.state = State.RETAINED_TIMEOUT;
                    }
                }
                ledger.pendingReset = true;
            }
        }

        Throwable primary = cancel(timer, null);
        if (early) {
            primary = combine(primary, reconcileTimer(ledger));
        } else {
            try {
                performPendingReset(ledger);
            } catch (Throwable resetFailure) {
                primary = combine(primary, resetFailure);
            }
        }
        if (primary != null) {
            throwUnchecked(primary);
        }
    }

    private void performPendingReset(final StreamLedger ledger) {
        final Http2LifecycleManager lifecycle;
        final ChannelHandlerContext ctx;
        synchronized (this) {
            if (!ledger.pendingReset || ledger.resetAttempted || connectionClosed || context == null
                            || lifecycleDelegate == null) {
                return;
            }
            ledger.pendingReset = false;
            ledger.resetAttempted = true;
            lifecycle = lifecycleDelegate;
            ctx = context;
        }

        final ChannelFuture resetFuture;
        try {
            resetFuture = lifecycle.resetStream(ctx, ledger.stream.id(), Http2Error.FLOW_CONTROL_ERROR.code(),
                            ctx.newPromise());
            if (resetFuture == null) {
                throw new NullPointerException("HTTP/2 resetStream returned null");
            }
            resetFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (!future.isSuccess()) {
                        Throwable failure = future.cause();
                        requestFailClosedConnectionClose(failure == null
                                        ? new IllegalStateException("HTTP/2 timeout reset failed") : failure);
                    }
                }
            });
            ctx.flush();
        } catch (Throwable failure) {
            requestFailClosedConnectionClose(failure);
            throwUnchecked(failure);
        }
    }

    private void requestFailClosedConnectionClose(Throwable primary) {
        synchronized (this) {
            if (connectionClosed) {
                return;
            }
            failClosedCloseRequested = true;
            if (failClosedCause == null) {
                failClosedCause = primary;
            } else {
                addSuppressed(failClosedCause, primary);
            }
        }
        attemptFailClosedConnectionClose();
    }

    private void attemptFailClosedConnectionClose() {
        final ChannelHandlerContext ctx;
        final Throwable primary;
        synchronized (this) {
            if (!failClosedCloseRequested || failClosedCloseAttempted || connectionClosed || context == null) {
                return;
            }
            failClosedCloseAttempted = true;
            ctx = context;
            primary = failClosedCause;
        }
        try {
            ChannelFuture closeFuture = ctx.close();
            if (closeFuture == null) {
                throw new NullPointerException("channel close returned null");
            }
            closeFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (!future.isSuccess()) {
                        Throwable closeFailure = future.cause();
                        if (closeFailure == null) {
                            closeFailure = new IllegalStateException("fail-closed channel close failed");
                        }
                        addSuppressed(primary, closeFailure);
                        try {
                            onConnectionClosed(primary == null ? closeFailure : primary);
                        } catch (Throwable cleanupFailure) {
                            ctx.fireExceptionCaught(cleanupFailure);
                        }
                    }
                }
            });
        } catch (Throwable closeFailure) {
            addSuppressed(primary, closeFailure);
            try {
                onConnectionClosed(primary == null ? closeFailure : primary);
            } catch (Throwable cleanupFailure) {
                addSuppressed(primary, cleanupFailure);
            }
        }
    }

    private void onConnectionClosed(Throwable cause) {
        List<AccountingFlowControlled> cleanup = new ArrayList<AccountingFlowControlled>();
        List<Cancellable> timers = new ArrayList<Cancellable>();
        ChannelHandlerContext callbackContext;
        synchronized (this) {
            if (connectionClosed) {
                return;
            }
            connectionClosed = true;
            callbackContext = context;
            for (StreamLedger ledger : streamLedgers.values()) {
                Cancellable timer = detachTimerLocked(ledger);
                if (timer != null) {
                    timers.add(timer);
                }
                for (AccountingFlowControlled wrapper : ledger.wrappers) {
                    if (!wrapper.callbackInvoked && wrapper.state != State.MERGED_AWAY) {
                        wrapper.callbackInvoked = true;
                        wrapper.state = State.CONNECTION_FORCED;
                        wrapper.remaining = 0;
                        cleanup.add(wrapper);
                    }
                }
            }
            streamLedgers.clear();
            liveBytes = 0;
            retainedBytes = 0;
        }

        Throwable primary = null;
        for (Cancellable timer : timers) {
            primary = cancel(timer, primary);
        }
        for (AccountingFlowControlled wrapper : cleanup) {
            try {
                wrapper.payload.error(callbackContext, cause);
            } catch (Throwable callbackFailure) {
                primary = combine(primary, callbackFailure);
            }
        }
        if (primary != null) {
            throwUnchecked(primary);
        }
    }

    private static Throwable combine(Throwable primary, Throwable secondary) {
        if (secondary == null) {
            return primary;
        }
        if (primary == null) {
            return secondary;
        }
        addSuppressed(primary, secondary);
        return primary;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary != null && secondary != null && primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        LibertyMaxQueuedBytesFlowController.<RuntimeException>throwAny(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwAny(Throwable failure) throws T {
        throw (T) failure;
    }

    private final class AccountingFlowControlled implements FlowControlled {
        private final Http2Stream stream;
        private final FlowControlled payload;
        private final long generation;
        private final long originalDeadline;
        private long remaining;
        private State state = State.LIVE;
        private PublicationState publicationState = PublicationState.RESERVED;
        private boolean timeoutDue;
        private boolean callbackInvoked;
        private boolean mergeInProgress;
        private long mergeGeneration;
        private ChannelHandlerContext pendingTerminalContext;
        private Throwable pendingTerminalCause;

        AccountingFlowControlled(Http2Stream stream, FlowControlled payload, long remaining,
                                 long generation, long originalDeadline) {
            this.stream = stream;
            this.payload = payload;
            this.remaining = remaining;
            this.generation = generation;
            this.originalDeadline = originalDeadline;
        }

        @Override
        public int size() {
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                if (remaining > Integer.MAX_VALUE) {
                    throw new IllegalStateException("flow-controlled size exceeds integer range");
                }
                return (int) remaining;
            }
        }

        @Override
        public void error(ChannelHandlerContext ctx, Throwable cause) {
            StreamLedger ledger;
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                if (callbackInvoked || state == State.MERGED_AWAY || state == State.COMPLETED
                                || state == State.ERRORED || state == State.CONNECTION_FORCED) {
                    return;
                }
                if (mergeInProgress) {
                    latchTerminal(ctx, cause);
                    return;
                }
                callbackInvoked = true;
                if (state == State.LIVE) {
                    debitLive(remaining);
                }
                state = State.ERRORED;
                remaining = 0;
                ledger = removeWrapperLocked(this);
            }

            Throwable primary = ledger == null ? null : reconcileTimer(ledger);
            try {
                payload.error(ctx, cause);
            } catch (Throwable callbackFailure) {
                primary = combine(primary, callbackFailure);
            }
            if (primary != null) {
                throwUnchecked(primary);
            }
        }

        private void latchTerminal(ChannelHandlerContext ctx, Throwable cause) {
            if (pendingTerminalCause == null) {
                pendingTerminalContext = ctx;
                pendingTerminalCause = cause;
            } else {
                addSuppressed(pendingTerminalCause, cause);
            }
        }

        @Override
        public void writeComplete() {
            StreamLedger ledger;
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                if (callbackInvoked || state != State.LIVE) {
                    return;
                }
                if (remaining != 0) {
                    throw new IllegalStateException("write completed with queued bytes remaining");
                }
                callbackInvoked = true;
                state = State.COMPLETED;
                ledger = removeWrapperLocked(this);
            }

            Throwable primary = ledger == null ? null : reconcileTimer(ledger);
            try {
                payload.writeComplete();
            } catch (Throwable callbackFailure) {
                primary = combine(primary, callbackFailure);
            }
            if (primary != null) {
                throwUnchecked(primary);
            }
        }

        @Override
        public void write(ChannelHandlerContext ctx, int allowedBytes) {
            int before;
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                if (state != State.LIVE || callbackInvoked) {
                    return;
                }
                before = payload.size();
                if (before < 0 || before != remaining) {
                    throw new IllegalStateException("flow-controlled size changed outside write");
                }
            }

            Throwable primary = null;
            try {
                payload.write(ctx, allowedBytes);
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                try {
                    int after = payload.size();
                    synchronized (LibertyMaxQueuedBytesFlowController.this) {
                        if (after < 0 || after > before) {
                            throw new IllegalStateException("flow-controlled size increased during write");
                        }
                        long delta = before - after;
                        if (state == State.LIVE) {
                            debitLive(delta);
                            remaining = after;
                        }
                    }
                } catch (Throwable accountingFailure) {
                    if (primary != null) {
                        primary.addSuppressed(accountingFailure);
                    } else {
                        throw accountingFailure;
                    }
                }
            }
        }

        @Override
        public boolean merge(ChannelHandlerContext ctx, FlowControlled next) {
            if (!(next instanceof LibertyMaxQueuedBytesFlowController.AccountingFlowControlled)) {
                return false;
            }
            AccountingFlowControlled other = (AccountingFlowControlled) next;
            final StreamLedger ledger;
            final long mergeGeneration;
            final long combined;
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                if (other.stream != stream || state != State.LIVE || other.state != State.LIVE
                                || callbackInvoked || other.callbackInvoked || mergeInProgress
                                || other.mergeInProgress || connectionClosed) {
                    return false;
                }
                ledger = streamLedgers.get(stream.id());
                if (ledger == null || !ledger.wrappers.contains(this) || !ledger.wrappers.contains(other)) {
                    return false;
                }
                combined = checkedAdd(remaining, other.remaining, "merged flow-controlled size overflow");
                mergeGeneration = nextGeneration(ledger);
                mergeInProgress = true;
                other.mergeInProgress = true;
                this.mergeGeneration = mergeGeneration;
                other.mergeGeneration = mergeGeneration;
            }

            final boolean merged;
            try {
                merged = payload.merge(ctx, other.payload);
            } catch (Throwable failure) {
                boolean transferred = false;
                try {
                    transferred = other.payload.size() == 0;
                } catch (Throwable inspectionFailure) {
                    addSuppressed(failure, inspectionFailure);
                }
                reconcileMergeFailure(ctx, other, ledger, mergeGeneration, transferred, failure);
                requestFailClosedConnectionClose(failure);
                throwUnchecked(failure);
                return false;
            }

            if (!merged) {
                ChannelHandlerContext firstContext;
                Throwable firstCause;
                ChannelHandlerContext secondContext;
                Throwable secondCause;
                synchronized (LibertyMaxQueuedBytesFlowController.this) {
                    clearMergeOwnership(mergeGeneration, other);
                    firstContext = pendingTerminalContext;
                    firstCause = pendingTerminalCause;
                    secondContext = other.pendingTerminalContext;
                    secondCause = other.pendingTerminalCause;
                    pendingTerminalContext = null;
                    pendingTerminalCause = null;
                    other.pendingTerminalContext = null;
                    other.pendingTerminalCause = null;
                }
                if (firstCause != null) {
                    error(firstContext, firstCause);
                }
                if (secondCause != null) {
                    other.error(secondContext, secondCause);
                }
                return false;
            }

            final int mergedSize;
            try {
                mergedSize = payload.size();
            } catch (Throwable failure) {
                reconcileMergeFailure(ctx, other, ledger, mergeGeneration, true, failure);
                requestFailClosedConnectionClose(failure);
                throwUnchecked(failure);
                return false;
            }

            boolean valid;
            ChannelHandlerContext terminalContext = null;
            Throwable terminalCause = null;
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                valid = streamLedgers.get(stream.id()) == ledger
                                && state == State.LIVE && other.state == State.LIVE
                                && !callbackInvoked && !other.callbackInvoked
                                && mergeInProgress && other.mergeInProgress
                                && this.mergeGeneration == mergeGeneration
                                && other.mergeGeneration == mergeGeneration;
                if (valid && (mergedSize < 0 || mergedSize > combined)) {
                    valid = false;
                }
                if (valid) {
                    debitLive(combined - mergedSize);
                    remaining = mergedSize;
                    other.remaining = 0;
                    other.callbackInvoked = true;
                    other.state = State.MERGED_AWAY;
                    terminalContext = pendingTerminalCause != null ? pendingTerminalContext : other.pendingTerminalContext;
                    terminalCause = pendingTerminalCause != null ? pendingTerminalCause : other.pendingTerminalCause;
                    pendingTerminalContext = null;
                    pendingTerminalCause = null;
                    other.pendingTerminalContext = null;
                    other.pendingTerminalCause = null;
                    clearMergeOwnership(mergeGeneration, other);
                    removeWrapperLocked(other);
                }
            }

            if (!valid) {
                IllegalStateException failure = new IllegalStateException("merge ownership changed during delegate merge");
                reconcileMergeFailure(ctx, other, ledger, mergeGeneration, false, failure);
                requestFailClosedConnectionClose(failure);
                throw failure;
            }
            if (terminalCause != null) {
                error(terminalContext, terminalCause);
            }
            Throwable timerFailure = reconcileTimer(ledger);
            if (timerFailure != null) {
                requestFailClosedConnectionClose(timerFailure);
                throwUnchecked(timerFailure);
            }
            return true;
        }

        private void reconcileMergeFailure(ChannelHandlerContext ctx, AccountingFlowControlled other,
                                           StreamLedger ledger, long generation, boolean transferred,
                                           Throwable failure) {
            List<AccountingFlowControlled> terminal = new ArrayList<AccountingFlowControlled>();
            synchronized (LibertyMaxQueuedBytesFlowController.this) {
                clearMergeOwnership(generation, other);
                if (transferred) {
                    remaining = checkedAdd(remaining, other.remaining, "failed merged ownership overflow");
                    other.remaining = 0;
                    other.callbackInvoked = true;
                    other.state = State.MERGED_AWAY;
                    removeWrapperLocked(other);
                    latchTerminal(ctx, failure);
                    terminal.add(this);
                } else {
                    terminal.add(this);
                    terminal.add(other);
                }
                for (AccountingFlowControlled owner : terminal) {
                    if (!owner.callbackInvoked) {
                        owner.callbackInvoked = true;
                        if (owner.state == State.LIVE) {
                            debitLive(owner.remaining);
                        } else if (owner.state == State.RETAINED_TIMEOUT) {
                            debitRetained(owner.remaining);
                        }
                        owner.state = State.ERRORED;
                        owner.remaining = 0;
                        removeWrapperLocked(owner);
                    }
                }
            }
            Throwable primary = reconcileTimer(ledger);
            for (AccountingFlowControlled owner : terminal) {
                try {
                    owner.payload.error(ctx, failure);
                } catch (Throwable callbackFailure) {
                    primary = combine(primary, callbackFailure);
                }
            }
            if (primary != null) {
                addSuppressed(failure, primary);
            }
        }

        private void clearMergeOwnership(long generation, AccountingFlowControlled other) {
            if (mergeGeneration == generation) {
                mergeInProgress = false;
                mergeGeneration = 0;
            }
            if (other.mergeGeneration == generation) {
                other.mergeInProgress = false;
                other.mergeGeneration = 0;
            }
        }
    }

    private static final class TimerSchedule {
        final StreamLedger ledger;
        final AccountingFlowControlled head;
        final long generation;
        final long deadline;
        final long epoch;
        final long delay;
        final ChannelHandlerContext context;

        TimerSchedule(StreamLedger ledger, AccountingFlowControlled head, long generation, long deadline,
                      long epoch, long delay, ChannelHandlerContext context) {
            this.ledger = ledger;
            this.head = head;
            this.generation = generation;
            this.deadline = deadline;
            this.epoch = epoch;
            this.delay = delay;
            this.context = context;
        }
    }

    private static final class StreamLedger {
        final Http2Stream stream;
        final ArrayDeque<AccountingFlowControlled> wrappers = new ArrayDeque<AccountingFlowControlled>();
        long nextGeneration;
        AccountingFlowControlled armedHead;
        long armedGeneration;
        long armedDeadline;
        Cancellable timer;
        long timerEpoch;
        boolean pendingReset;
        boolean resetAttempted;

        StreamLedger(Http2Stream stream) {
            this.stream = stream;
        }
    }
}
