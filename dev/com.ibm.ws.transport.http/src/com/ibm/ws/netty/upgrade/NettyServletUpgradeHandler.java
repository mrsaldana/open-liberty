/*******************************************************************************
 * Copyright (c) 2023, 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.netty.upgrade;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.http.dispatcher.internal.HttpDispatcher;
import com.ibm.ws.http.netty.NettyHttpConstants;
import com.ibm.ws.transport.access.TransportConnectionAccess;
import com.ibm.ws.transport.access.TransportConstants;
import com.ibm.wsspi.channelfw.VirtualConnection;
import com.ibm.wsspi.tcpchannel.TCPReadCompletedCallback;
import com.ibm.wsspi.tcpchannel.TCPReadRequestContext;

import com.ibm.wsspi.bytebuffer.WsByteBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CoalescingBufferQueue;
import io.netty.channel.VoidChannelPromise;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.util.concurrent.ScheduledFuture;
import io.openliberty.netty.internal.impl.QuiesceState;

import io.netty.util.ReferenceCountUtil;

/**
 *
 */
public class NettyServletUpgradeHandler extends ChannelDuplexHandler {

    private static final TraceComponent tc = Tr.register(NettyServletUpgradeHandler.class);

    public static final String NAME = "NettyServletUpgradeHandler";

    private final Channel channel;
    private ChannelHandlerContext context;
    private final CoalescingBufferQueue queue;

    private final ReentrantLock readLock = new ReentrantLock();
    private final Condition readCondition = readLock.newCondition();

    private volatile long minBytesToRead = 0L;
    private volatile boolean isReadingAsync = false;

    private final AtomicInteger waitingThreads = new AtomicInteger(0);
    private final AtomicBoolean peerClosed = new AtomicBoolean(false);
    private final AtomicBoolean immediateTimeout = new AtomicBoolean(false);
    private final AtomicInteger queuedBytes = new AtomicInteger(0);
    
    private TCPReadCompletedCallback callback;
    private VirtualConnection vc;
    private TCPReadRequestContext readContext;


    public NettyServletUpgradeHandler(Channel channel) {
        this.channel = channel;
        this.queue = new CoalescingBufferQueue(channel);        
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) throws Exception {
        this.context = context;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        // java.io.EOFException: Connection closed: Read failed.  Possible end of stream encountered. local=ip:port remote=ip:port
        if (!peerClosed.get() && (event instanceof ChannelInputShutdownEvent || event instanceof ChannelInputShutdownReadComplete)) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, "NettyServletUpgradeHandler ChannelInputShutdownEvent kicked off for channel " + channel);
            }
            peerClosed.set(true);
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " userEventTriggered: ChannelInputShutdownEvent, "
                                   + "isAsync=" + isReadingAsync
                                   + " queuedBytes=" + queuedBytes.get()
                                   + " callback=" + callback);
            }

            if (isReadingAsync && callback != null) {
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(this, tc, "NettyServletUpgradeHandler ChannelInputShutdownEvent reading async found!!");
                }
                isReadingAsync = false;
                HttpDispatcher.getExecutorService().execute(() -> {
                    try{
                        callback.error(vc, readContext, new EOFException("Connection closed: Read failed. Possible end of stream. local=" +
                                channel.localAddress() + " remote=" + channel.remoteAddress()));
                    } catch (Exception ignore){}
                });
                return;
            }
            if(queuedDataSize() > 0){
                signalReadReady();
            }
            super.userEventTriggered(context, event);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ByteBuf) {
            clearReadPending();
            ByteBuf buf = (ByteBuf) message;
            final int n = buf.readableBytes();
            queue.add(buf);
            final int total = queuedBytes.addAndGet(n);

            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " channelRead: bytes=" + n
                                   + " totalQueued=" + total
                                   + " isAsync=" + isReadingAsync
                                   + " waitingThreads=" + waitingThreads.get());
            }

            if (isReadingAsync && total >= minBytesToRead) {
                isReadingAsync = false;
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(this, tc, logId()
                                       + " channelRead: async threshold satisfied, dispatchAsyncComplete; "
                                       + "minBytesToRead=" + minBytesToRead);
                }
                dispatchAsyncComplete();
                // if (callback != null) {
                //     HttpDispatcher.getExecutorService().execute(() -> {
                //         try {
                //             callback.complete(vc, readContext);
                //         } catch (Exception ignore) {
                //         }
                //     });
                // }
            // } else if (queuedBytes.get() >= minBytesToRead) {
            //     signalReadReady();
            // }
            } else if (!isReadingAsync && waitingThreads.get() > 0 && total > 0) {
            // Blocking readers are waiting; wake them as soon as *any* data arrives
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " channelRead: waking blocking readers, waitingThreads="
                                   + waitingThreads.get());
            }
            signalReadReady();
        }
            return;
        }
        super.channelRead(context, message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        peerClosed.set(true);
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " channelInactive: queuedBytes=" + queuedBytes.get()
                               + " isAsync=" + isReadingAsync
                               + " callback=" + callback);
        }
        if (isReadingAsync && callback != null) {
            isReadingAsync = false;
            ExecutorService executor = HttpDispatcher.getExecutorService();
            if (executor == null) {
                // Dispatcher is already deactivated - nothing to schedule.
                return;
            }

            executor.execute(() -> {
                try {
                    callback.error(vc, readContext,
                                   new EOFException("Connection closed: Read failed. Possible end of stream. local=" +
                                                    channel.localAddress() + " remote=" + channel.remoteAddress()));
                } catch (Exception ignore) {}
            });
        }
        super.channelInactive(context);
    }

    public void immediateTimeout() {
        if (context != null && !context.executor().inEventLoop()) {
            context.executor().execute(this::immediateTimeout);
            return;
        }
        immediateTimeout.set(true);
        signalReadReady();

        if (isReadingAsync && callback != null) {
            isReadingAsync = false;
            HttpDispatcher.getExecutorService().execute(() -> {
                try {
                    callback.error(vc, readContext, new SocketTimeoutException("Immediate timeout requested"));
                } catch (Exception ignore) {
                }
            });
        }
    }

    private void signalReadReady() {
        readLock.lock();
        try {
            readCondition.signalAll();
        } finally {
            readLock.unlock();
        }
    }

    public void waitForDataRead(long waitMillis) throws InterruptedException {
        waitingThreads.incrementAndGet();
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " waitForDataRead: enter, waitMillis=" + waitMillis
                               + " queuedBytes=" + queuedBytes.get()
                               + " immediateTimeout=" + immediateTimeout.get()
                               + " peerClosed=" + peerClosed.get());
        }
        try {
            readLock.lock();
            try {
                while (!immediateTimeout.get() && queuedDataSize() == 0 && channel.isActive()) {
                    if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                        Tr.debug(this, tc, logId()
                                           + " waitForDataRead: waiting, queuedBytes="
                                           + queuedBytes.get());
                    }
                    requestReadKick();
                    if (!readCondition.await(waitMillis, TimeUnit.MILLISECONDS))
                        break;
                }
            } finally {  
                readLock.unlock();
                if (immediateTimeout.get()) {
                    immediateTimeout.set(false);
                }
            }
        } finally {
            int left = waitingThreads.decrementAndGet();
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " waitForDataRead: exit, queuedBytes=" + queuedBytes.get()
                                   + " immediateTimeout=" + immediateTimeout.get()
                                   + " peerClosed=" + peerClosed.get()
                                   + " waitingThreads=" + left);
            }
        }
    }

    public boolean isAsyncReadArmed(){
        return isReadingAsync;
    }

    public long setToBuffer() {
        
        if (readContext == null || !containsQueuedData())
            return 0L;

        final WsByteBuffer[] buffers = readContext.getBuffers();
        if (buffers == null || buffers.length == 0 || buffers[0] == null)
            return 0L;

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " setToBuffer: starting, queuedBytes=" + queuedBytes.get()
                               + " readContext=" + readContext
                               + " buffers=" + java.util.Arrays.toString(buffers));
        }


        final AtomicLong written = new AtomicLong(0L);
        final Runnable task = () -> {
            int capacity = 0;
            for (WsByteBuffer b : buffers) {
                if (b == null)
                    break;
                capacity += Math.max(0, b.remaining());
            }
            final int available = queuedBytes.get();
            final int toRead = Math.min(capacity, available);
            if (toRead <= 0) {
                written.set(0L);
                return;
            }

            ByteBuf chunk = read(toRead, null); 
            try {
                int remaining = chunk.readableBytes();
                int copied = 0;

                for (WsByteBuffer b : buffers) {
                    if (b == null || remaining == 0)
                        break;
                    final java.nio.ByteBuffer dst = b.getWrappedByteBuffer();
                    final int can = Math.min(dst.remaining(), remaining);
                    if (can <= 0)
                        continue;

                    final int lim = dst.limit();
                    final int pos = dst.position();
                    dst.limit(pos + can);
                    chunk.readBytes(dst);
                    dst.limit(lim);

                    b.position(dst.position());
                    remaining -= can;
                    copied += can;
                }

            
                if (copied > 0) {
                    queuedBytes.addAndGet(-copied);
                }
                written.set(copied);
            } finally {
                chunk.release();
            }
        };

        if (context != null && !context.executor().inEventLoop()) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            context.executor().execute(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } else {
            task.run();
        }

        return written.get();
    }

    @Override
    public void close(ChannelHandlerContext context, ChannelPromise promise) throws Exception {
        peerClosed.set(true);
        super.close(context, promise);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext context) throws Exception {
        clearReadPending();
        if (!context.channel().config().isAutoRead()
            && !peerClosed.get() && context.channel().isActive()) {

            final int dataSize = queuedDataSize();
            if(isReadingAsync){
                if(dataSize<minBytesToRead){
                    requestReadKick();
                }
            } else if(waitingThreads.get()>0){
                if (dataSize==0){
                    requestReadKick();
                }
            }
        }
        super.channelReadComplete(context);
    }

    public boolean containsQueuedData() {
        return queuedBytes.get() > 0;
    }

    public int queuedDataSize() {
        return queuedBytes.get();
    }

    public boolean isImmediateTimeout() {
        return immediateTimeout.get();
    }

    public synchronized ByteBuf read(int size, ChannelPromise promise) {
        if (context != null && !context.executor().inEventLoop()) {
            throw new IllegalStateException("Upgrade queue read must run on the channel EventLoop");
        }
        if (!containsQueuedData())
            return Unpooled.EMPTY_BUFFER;

        ByteBuf out = (promise == null) ? queue.remove(size, new VoidChannelPromise(channel, true)) : queue.remove(size, promise);


        return out;
    }

    public void setReadListener(TCPReadCompletedCallback cb) {
        this.callback = cb;
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId() + " setReadListener: " + cb);
        }
    }

    public void queueAsyncRead(long minBytesToRead) {
        this.minBytesToRead = (long) Math.max(1L, minBytesToRead);
        this.isReadingAsync = true;

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " queueAsyncRead: minBytesToRead=" + this.minBytesToRead
                               + " queuedBytes=" + queuedBytes.get()
                               + " callback=" + callback);
        }

        requestReadKick();

        final int q = queuedBytes.get();
        if (q >= this.minBytesToRead && callback != null) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " queueAsyncRead: data already available, "
                                   + "q=" + q + " >= " + this.minBytesToRead
                                   + " -> dispatchAsyncComplete()");
            }
            this.isReadingAsync = false;
            dispatchAsyncComplete();
            // HttpDispatcher.getExecutorService().execute(() -> {
            //     try {
            //         callback.complete(vc, readContext);
            //     } catch (Throwable t) {
            //         try {
            //             callback.error(vc, readContext,
            //                            (t instanceof IOException) ? (IOException) t : new EOFException(String.valueOf(t)));
            //         } catch (Throwable ignore) {
            //         }
            //     }
            // });
        }
    }

    private void dispatchAsyncComplete() {
        final TCPReadCompletedCallback cb = this.callback;
        if (cb == null) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " dispatchAsyncComplete: no callback set, returning");
            }
            return;
        }

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " dispatchAsyncComplete: queuedBytes=" + queuedBytes.get()
                               + " readContext=" + readContext
                               + " callback=" + cb);
        }

        HttpDispatcher.getExecutorService().execute(() -> {
            try {
                if (readContext != null) {
                    // This copies up to the space in readContext.getBuffers()
                    // and decrements queuedBytes accordingly.
                    long copied = setToBuffer();
                    if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                        Tr.debug(this, tc, "dispatchAsyncComplete copied=" + copied
                                           + " queuedAfter=" + queuedBytes.get());
                    }
                }
                
                cb.complete(vc, readContext);
            } catch (Throwable t) {
                // Wrap anything into an IOException for the callback.error signature
                IOException ioe = (t instanceof IOException) ? (IOException) t : new EOFException(String.valueOf(t));
                try {
                    cb.error(vc, readContext, ioe);
                } catch (Throwable ignore) {
                }
            }
        });
    }

    private void clearReadPending() {
        AtomicBoolean pending = channel.attr(NettyHttpConstants.READ_PENDING).get();
        if (pending != null) {
            pending.set(false);
        }
    }

    private void requestReadKick() {
        if (channel.config().isAutoRead()) {
            return;
        }

        AtomicBoolean pending = channel.attr(NettyHttpConstants.READ_PENDING).get();
        if (pending == null) {
            pending = new AtomicBoolean(false);
            channel.attr(NettyHttpConstants.READ_PENDING).set(pending);
        }
        if (!pending.compareAndSet(false, true)) {
            return;
        }

        channel.eventLoop().execute(channel::read);
    }

    public boolean peerClosedConnection() {
        return peerClosed.get();
    }

    public TCPReadCompletedCallback getReadListener() {
        return callback;
    }

    public void setVC(VirtualConnection vc) {
        this.vc = vc;
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId() + " setVC: " + vc);
        }
    }

    public void setTCPReadContext(TCPReadRequestContext tcpReadContext) {
        this.readContext = tcpReadContext;
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId() + " setTCPReadContext: " + tcpReadContext);
        }
    }

    //remove after debug
    private String logId() {
        return "[UpgradeHandler ch=" + channel.id() + " ctx=" + System.identityHashCode(this) + "]";
    }

}