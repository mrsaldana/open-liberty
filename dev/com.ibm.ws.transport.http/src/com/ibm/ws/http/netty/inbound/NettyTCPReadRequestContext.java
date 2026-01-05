/*******************************************************************************
 * Copyright (c) 2023, 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.http.netty.inbound;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.http.channel.internal.HttpChannelConfig;
import com.ibm.ws.http.channel.internal.HttpMessages;
import com.ibm.ws.http.channel.internal.inbound.HttpInputStreamImpl;
import com.ibm.ws.http.dispatcher.internal.HttpDispatcher;
import com.ibm.ws.http.netty.NettyHttpChannelConfig;
import com.ibm.ws.netty.upgrade.NettyServletUpgradeHandler;
import com.ibm.ws.transport.access.TransportConstants;
import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.channelfw.VirtualConnection;
import com.ibm.wsspi.http.HttpInputStream;
import com.ibm.wsspi.http.ee7.HttpInputStreamEE7;
import com.ibm.wsspi.tcpchannel.TCPConnectionContext;
import com.ibm.wsspi.tcpchannel.TCPReadCompletedCallback;
import com.ibm.wsspi.tcpchannel.TCPReadRequestContext;

import io.openliberty.http.netty.timeout.TimeoutHandler;
import io.openliberty.http.options.TcpOption;

import com.ibm.ws.http.netty.NettyHttpConstants;
import com.ibm.ws.http.netty.pipeline.inbound.HttpDispatcherHandler;
import com.ibm.wsspi.channelfw.ChannelFrameworkFactory;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

/**
 *
 */
public class NettyTCPReadRequestContext implements TCPReadRequestContext {

    private static final TraceComponent tc = Tr.register(NettyTCPReadRequestContext.class, HttpMessages.HTTP_TRACE_NAME, HttpMessages.HTTP_BUNDLE);

    private int channelDefaultTimeout = (int) TcpOption.INACTIVITY_TIMEOUT.getDefaultValue();

    private final NettyTCPConnectionContext connectionContext;
    private final Channel nettyChannel;

    private WsByteBuffer[] buffers;
    private final WsByteBuffer[] defaultBuffers = new WsByteBuffer[1];
    
    private VirtualConnection vc = null;
    private int jitAllocateSize = 0;
    private boolean jitAllocateAction = false;

    private volatile boolean aborted = false;

    private final NonUpgradedSyncReadSignal nonUpgradedSignal;

    public NettyTCPReadRequestContext(NettyTCPConnectionContext connectionContext, Channel nettyChannel) {
        this.connectionContext = connectionContext;
        this.nettyChannel = nettyChannel;

        this.nonUpgradedSignal = new NonUpgradedSyncReadSignal(nettyChannel);

        HttpChannelConfig config = nettyChannel.attr(NettyHttpConstants.HTTP_CONFIG).get();
        if(config != null && config instanceof NettyHttpChannelConfig){
            this.channelDefaultTimeout = (int) ((NettyHttpChannelConfig) config).get(TcpOption.INACTIVITY_TIMEOUT);
        }
    }

    @Override
    public void clearBuffers() {
        if(this.buffers != null){
            for(WsByteBuffer buffer: this.buffers){
                if(buffer == null) break;
                buffer.clear();
            }
        }
    }

    @Override
    public TCPConnectionContext getInterface() {
        return this.connectionContext;
    }

    @Override
    public Socket getSocket() {
        throw new UnsupportedOperationException("Can not get the socket from a Netty connection!");
    }

    private HttpInputStreamImpl input() throws IOException {
    
        HttpInputStreamImpl in = null;
        if (vc != null) {
            Object candidate = vc.getStateMap().get(NettyHttpConstants.VC_HTTP_INPUT_STREAM);
            if (candidate instanceof HttpInputStreamImpl) {
                in = (HttpInputStreamImpl) candidate;
            }
            if (in == null) {
                Object sid = vc.getStateMap().get(NettyHttpConstants.VC_HTTP2_STREAM_ID);
                if (sid instanceof String) {
                    HttpDispatcherHandler disp =
                            nettyChannel.pipeline().get(HttpDispatcherHandler.class);
                    if (disp != null) {
                        HttpInputStream s = disp.getStream((String) sid);
                        if (s instanceof HttpInputStreamImpl) {
                            in = (HttpInputStreamImpl) s;
                            // Optionally cache it on VC for next time:
                            vc.getStateMap().put(NettyHttpConstants.VC_HTTP_INPUT_STREAM, in);
                        }
                    }
                }
            }
        }
        // if (in == null) {
        //     Object stream = nettyChannel.attr(NettyHttpConstants.HTTP_INPUT_STREAM).get();
        //     if (stream instanceof HttpInputStreamImpl) {
        //         in = (HttpInputStreamImpl) stream;
        //     }
        // }
        if (in == null) {
            throw new IOException("HTTP input stream not initialized for channel " + nettyChannel);
        }
        return in;
    }


    @Override
    public long read(long numBytes, int timeout) throws IOException {
         if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                            + " read(sync): numBytes=" + numBytes
                            + " timeout=" + timeout
                            + " aborted=" + aborted
                            + " channelActive=" + nettyChannel.isActive()
                            + " logicalUpg=" + isLogicallyUpgraded()
                            + " hasUpgradeHandler=" + hasUpgradeHandler());
         }

        if(aborted) throw new IOException("I/O Aborted");


        if (!nettyChannel.isActive()) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, "Found closed connection on read for channel: " + nettyChannel);
            }
            throw new EOFException("Connection closed: Read failed. Possible end of stream encountered. local="
                                   + nettyChannel.localAddress()
                                   + " remote="
                                   + nettyChannel.remoteAddress());
        }

        assertNotInEventLoop("TCPReadRequestContext.read(sync)");

        final boolean logicalUpg = isLogicallyUpgraded();
        final boolean handlerReady = hasUpgradeHandler();

        // If we're logically upgraded but the upgrade handler is not yet in place,
        // DO NOT touch HttpInputStreamImpl. Just report "no data" for now.
        if (logicalUpg && !handlerReady) {
            return 0L;
        }

        if (timeout == IMMED_TIMEOUT) {
            if (hasUpgradeHandler())
                ensureUpgradeHandler().immediateTimeout();
            return 0L;
        }

        if (timeout == ABORT_TIMEOUT) {
            aborted = true;
            if (hasUpgradeHandler())
                ensureUpgradeHandler().immediateTimeout();
            return 0L;
        }

        ensureBuffersOrJIT(numBytes, false);

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                            + " read(sync): dispatching to "
                            + (hasUpgradeHandler() ? "upgradedSyncRead" : "nonUpgradedSyncRead"));
        }

        if(numBytes == 0){
            return hasUpgradeHandler() ? upgradedImmediateDrain() : nonUpgradedImmediateDrain();
        }

        return hasUpgradeHandler() ? upgradedSyncRead(numBytes, timeout) : nonUpgradedSyncRead(numBytes, timeout);
    }

    private long nonUpgradedSyncRead(long numBytes, int timeout) throws IOException {
        final HttpInputStreamImpl in = input();
        if (buffers == null || buffers.length == 0 || buffers[0] == null)
            throw new IOException("Buffers not set for read()");

        if (numBytes <= 0) {
            int available;
            try {
                available = Math.max(0, in.available());
            } catch (IOException ioe) {
                available = 0;
            }
            if (available <= 0)
                return 0L;

            int capacity = 0;
            for (WsByteBuffer buffer : buffers) {
                if (buffer == null)
                    break;
                capacity += buffer.remaining();
            }
            final int chunk = Math.min(8192, Math.min(available, capacity));
            final byte[] temp = new byte[chunk];
            final int n = in.read(temp, 0, chunk);
            if (n > 0) {
                int off = 0;
                for (WsByteBuffer buffer : buffers) {
                    if (buffer == null || off >= n)
                        break;
                    off += copyInto(buffer, temp, off, n - off);
                }
                return n;
            }
            return 0L;
        }

        final int effectiveTimeout = normalizeTimeout(timeout);
        final boolean wasAuto = pushAutoRead();
        TimeoutHandler.ReadOpToken token = null;
        try {
            if (effectiveTimeout > 0) {
                token = TimeoutHandler.armReadOp(nettyChannel, effectiveTimeout, nonUpgradedSignal::signal);
            }

            ensureReadIfManual(); 
            return nonUpgradedBlockingReadLoop(in, numBytes, effectiveTimeout, nonUpgradedSignal);
        } finally {
            if (token != null) {
                try { token.close(); } catch (Throwable ignore) {}
            }
            popAutoRead(wasAuto);
        }
    }

    private long upgradedSyncRead(long numBytes, int timeout) throws IOException {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " upgradedSyncRead: numBytes=" + numBytes
                               + " timeout=" + timeout
                               + " queuedBytes=" + ensureUpgradeHandler().queuedDataSize());
        }
        final NettyServletUpgradeHandler h = ensureUpgradeHandler();
        h.setTCPReadContext(this);
        h.setVC(vc);

        if (numBytes <= 0) {
            ensureReadIfManual();
            return h.containsQueuedData() ? h.setToBuffer() : 0L;
        }

        final int t = normalizeTimeout(timeout);
        final long need = Math.max(1L, numBytes);
        
        final boolean wasAuto = pushAutoRead(); 
        TimeoutHandler.ReadOpToken token = null;

        try {

            if(t != NO_TIMEOUT){
                token = TimeoutHandler.armReadOp(nettyChannel, t, () ->{
                    try{
                        h.immediateTimeout();

                    }catch(Throwable ignore) {}
                });
            }

            ensureReadIfManual();

            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " upgradedSyncRead: entering wait loop, need=" + need
                                   + " currentQueued=" + h.queuedDataSize());
            }
            if (h.containsQueuedData() && h.queuedDataSize() >= need) {
                long copied = h.setToBuffer();
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(this, tc, logId()
                                       + " upgradedSyncRead: returning copied=" + copied
                                       + " queuedAfter=" + h.queuedDataSize());
                }
                return copied;
            }

            while (nettyChannel.isActive()) {
                if (t != NO_TIMEOUT && TimeoutHandler.readOpTimedOut(nettyChannel)) {
                    throw new SocketTimeoutException("Failed to read data within the specified timeout.");
                }

                try {
                    h.waitForDataRead(0L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for upgraded read data.", ie);
                }

                if (h.peerClosedConnection() && !h.containsQueuedData()) {
                    throw new IOException("Peer closed connection during read");
                }

                if (h.containsQueuedData() && ((long) h.queuedDataSize()) >= need) {
                    return h.setToBuffer();
                }

                ensureReadIfManual();
            }
            throw new EOFException("Channel inactive during read");
        } finally {
            if (token != null) {
                try { 
                    token.close(); 
                } catch (Throwable ignore) {}
            }
            popAutoRead(wasAuto); 
        }
    }

    @Override
    //@FFDCIgnore(EOFException.class)
    public VirtualConnection read(long numBytes, TCPReadCompletedCallback callback, boolean forceQueue, int timeout) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " read(async): numBytes=" + numBytes
                               + " timeout=" + timeout
                               + " forceQueue=" + forceQueue
                               + " callback=" + callback
                               + " logicalUpg=" + isLogicallyUpgraded()
                               + " hasUpgradeHandler=" + hasUpgradeHandler());
        }
        if (aborted) {
            if (callback != null) {
                HttpDispatcher.getExecutorService().execute(
                                                            () -> callback.error(vc, this, new EOFException("I/O aborted")));
            }
            return null;
        }

        if (!nettyChannel.isActive()) {
            if (callback != null) {
                HttpDispatcher.getExecutorService().execute(
                                                            () -> callback.error(vc, this, new EOFException("Channel closed.")));
            }
            return null;
        }

        assertNotInEventLoop("TCPReadRequestContext.read(async)");

        boolean logicalUpg = isLogicallyUpgraded();
        boolean handlerReady = hasUpgradeHandler();

        if (logicalUpg && handlerReady) {

            final int effectiveTimeout = normalizeTimeout(timeout);
            if (effectiveTimeout != IMMED_TIMEOUT && effectiveTimeout != ABORT_TIMEOUT) {
                ensureBuffersOrJIT(numBytes, true);
            }
            return upgradedAsyncRead(numBytes, callback, forceQueue, timeout);
        }

        if (logicalUpg && !handlerReady) {
            
            return null;
        }

        final int effectiveTimeout = normalizeTimeout(timeout);

        if (effectiveTimeout == IMMED_TIMEOUT)
            return null;
        if (effectiveTimeout == ABORT_TIMEOUT) {
            aborted = true;
            return null;
        }

        ensureBuffersOrJIT(numBytes, true);

        final boolean wasAuto = pushAutoRead();

        final NonUpgradedAsyncReadTask task =
            new NonUpgradedAsyncReadTask(this, vc, callback, numBytes, effectiveTimeout, wasAuto);

        if (effectiveTimeout > 0) {
            TimeoutHandler.ReadOpToken readToken = TimeoutHandler.armReadOp(nettyChannel, effectiveTimeout, task::kickOnTimeout);
            task.setToken(readToken);
        }

        nettyChannel.attr(NettyHttpConstants.ASYNC_READ_CALLBACK).set(task);

        if(!isLogicallyUpgraded()){
            try {
                HttpInputStreamImpl in2 = input();
                boolean isEE7 = (in2 instanceof HttpInputStreamEE7);
                if (in2.available() > 0 || (isEE7 && ((HttpInputStreamEE7) in2).isFinished())) {
                    Runnable pending = nettyChannel.attr(NettyHttpConstants.ASYNC_READ_CALLBACK).getAndSet(null);
                    if (pending != null)
                        HttpDispatcher.getExecutorService().execute(pending);
                }
            } catch (IOException ignore) { }

            ensureReadIfManual();
            return null;
        }
        return null;
    }

    public VirtualConnection upgradedAsyncRead(long numBytes, TCPReadCompletedCallback callback, boolean forceQueue, int timeout) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(this, tc, logId()
                               + " upgradedAsyncRead: numBytes=" + numBytes
                               + " timeout=" + timeout
                               + " forceQueue=" + forceQueue
                               + " callback=" + callback);
        }
        final NettyServletUpgradeHandler h = ensureUpgradeHandler();
        h.setTCPReadContext(this);
        h.setVC(vc);

        if (timeout == IMMED_TIMEOUT) {
            h.immediateTimeout();
            return null;
        }
        if (timeout == ABORT_TIMEOUT) {
            aborted = true;
            h.immediateTimeout();
            return null;
        }

        final long need = Math.max(1L, numBytes);

        if (callback == null) {
            return null;
        }

        final boolean wasAuto = pushAutoRead(); 
        try{
            ensureReadIfManual();

            if (h.containsQueuedData() && h.queuedDataSize() >= need) {
                long copied = h.setToBuffer();
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(this, tc, logId()
                                    + " upgradedAsyncRead: fast-path copied=" + copied
                                    + " queuedAfter=" + h.queuedDataSize()
                                    + " forceQueue=" + forceQueue);
                }
                if (!forceQueue) {
                    popAutoRead(wasAuto);
                    return vc; 
                }
                HttpDispatcher.getExecutorService().execute(() -> {
                    try {
                        callback.complete(vc, this);
                    } catch (Throwable ignore) {
                    } finally {
                        popAutoRead(wasAuto);
                    }
                });
                return null;
            }

            final UpgradedAsyncReadCallback wrapped = new UpgradedAsyncReadCallback(this, callback, wasAuto);
            h.setReadListener(wrapped);

            final int t = normalizeTimeout(timeout);
            if (t != NO_TIMEOUT) {
                wrapped.armTimeout(nettyChannel, t, () -> {
                    try {
                        h.immediateTimeout();
                    } catch (Throwable ignore) {}
                });
            }

            h.queueAsyncRead(need);

            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                + " upgradedAsyncRead: queued async read, need=" + need
                                + " queuedBytes=" + h.queuedDataSize());
            }

            ensureReadIfManual();
            return null;

        } catch (RuntimeException | Error e) {
            // ensure autoRead is restored if we fail before callback path runs
            popAutoRead(wasAuto);
            throw e;
        }   
    }

    @Override
    public void setJITAllocateSize(int numBytes) {
        this.jitAllocateSize = numBytes;
    }

    @Override
    public boolean getJITAllocateAction() {
        return this.jitAllocateAction;
    }

    @Override
    public WsByteBuffer[] getBuffers() {
        return this.buffers;
    }

    @Override
    public void setBuffers(WsByteBuffer[] bufs) {
        this.buffers = bufs;
    }

    @Override
    public WsByteBuffer getBuffer() {
        return (this.buffers == null) ? null : this.buffers[0];
    }

    @Override
    public void setBuffer(WsByteBuffer buf) {
        this.defaultBuffers[0] = null;
        if (buf != null) {
            this.buffers = this.defaultBuffers;
            this.buffers[0] = buf;
        } else {
            this.buffers = null;
        }
    }

    public void setVC(VirtualConnection vc) {
        this.vc = vc;
    }

    private int normalizeTimeout(int timeout) {
        if (timeout == NO_TIMEOUT)
            return NO_TIMEOUT;
        if (timeout == USE_CHANNEL_TIMEOUT)
            return channelDefaultTimeout;
        if (timeout == IMMED_TIMEOUT)
            return IMMED_TIMEOUT;
        if (timeout == ABORT_TIMEOUT)
            return ABORT_TIMEOUT;
        return (timeout <= 0) ? channelDefaultTimeout : timeout;
    }

    private void ensureReadIfManual() {
        if (!nettyChannel.config().isAutoRead()) {
            nettyChannel.eventLoop().execute(nettyChannel::read);
        }
    }

    private boolean isLogicallyUpgraded() {

        if (hasUpgradeHandler()) {
            return true;
        }

        if(vc == null){
            return false;
        }
        
        Object flag = vc.getStateMap().get(TransportConstants.UPGRADED_CONNECTION);
        if ("true".equalsIgnoreCase(String.valueOf(flag))) {
            return true;
        }
        flag = vc.getStateMap().get(TransportConstants.UPGRADED_LISTENER);
        if ("true".equalsIgnoreCase(String.valueOf(flag))) {
            return true;
        }

        flag = vc.getStateMap().get(TransportConstants.CLOSE_NON_UPGRADED_STREAMS);
        if (flag != null && !"false".equalsIgnoreCase(String.valueOf(flag))) {
            // Values like "true" or "CLOSED_NON_UPGRADED_STREAMS" mean this VC
            // is in an upgrade scenario where the normal HTTP request is done.
            return true;
        }

        return false;
    }

    private boolean hasUpgradeHandler() {
        return nettyChannel.pipeline().get(NettyServletUpgradeHandler.class) != null;
    }

    // private boolean isUpgraded() {
    //     if(nettyChannel.pipeline().get(NettyServletUpgradeHandler.class) != null){
    //         System.out.println("DEBUG: isupgraded() true");
    //         return true;
    //     }
    //     if (vc != null) {
    //         Object flag = vc.getStateMap().get(com.ibm.ws.transport.access.TransportConstants.UPGRADED_CONNECTION);
    //         if ("true".equalsIgnoreCase(String.valueOf(flag))) {
    //             System.out.println("DEBUG vc upgrade flag true");
    //             return true;
    //         }
    //     }
    //     if (vc != null) {
    //         Object flag = vc.getStateMap().get(com.ibm.ws.transport.access.TransportConstants.UPGRADED_LISTENER);
    //         if ("true".equalsIgnoreCase(String.valueOf(flag))) {
    //             System.out.println("DEBUG vc upgrade flag true");
    //             return true;
    //         }
    //     }
    //     return false;
    // }

    private NettyServletUpgradeHandler ensureUpgradeHandler() {
        NettyServletUpgradeHandler h = nettyChannel.pipeline().get(NettyServletUpgradeHandler.class);
        
        if(h==null){
            //TODO lazy initialization due to wsoc not triggering upgrade event. Find missing location to throw event .
            if(isWsocUpgrade()){
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(this, tc, logId()
                                       + " ensureUpgradeHandler: installing new handler lazily for WSOC upgrade");
                }
                Tr.debug(tc, "Installing upgrade handler for WSOC upgrade");
                h = new NettyServletUpgradeHandler(nettyChannel);
                h.setTCPReadContext(this);
                h.setVC(vc);
                if(nettyChannel.pipeline().get("ServletUpgradeHandler") == null){
                    nettyChannel.pipeline().addLast("ServletUpgradeHandler", h);
                }


                return h;
            } else {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                + " ensureUpgradeHandler: found existing handler " + h);
            }
        }
            throw new IllegalStateException("Channel marked upgraded but no NettyServletUpgradeHandler in pipeline");
        } else {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(this, tc, logId()
                                   + " ensureUpgradeHandler: found existing handler " + h);
            }
        }
        h.setTCPReadContext(this);
        h.setVC(vc);

        return h;
    }

    private boolean isWsocUpgrade(){
        if(vc == null){
            return false;
        }
        Object upgradeConn = vc.getStateMap().get(com.ibm.ws.transport.access.TransportConstants.UPGRADED_CONNECTION);
        Object webConn = vc.getStateMap().get(com.ibm.ws.transport.access.TransportConstants.UPGRADED_WEB_CONNECTION_OBJECT);
        return "true".equalsIgnoreCase(String.valueOf(upgradeConn)) && webConn != null;
    }

    private long nonUpgradedImmediateDrain() throws IOException {
        ensureReadIfManual();
        final HttpInputStreamImpl in = input();
        if (buffers == null || buffers.length == 0 || buffers[0] == null) return 0L;

        final int available = Math.max(0, in.available());
        if (available <= 0) return 0L;
        
        int want = 0;
        for(WsByteBuffer buffer: buffers){
            if (buffer == null) break;

            want += buffer.remaining();
        }
        if (want <= 0) return 0L;

        final int toRead = Math.min(available, want);
        final byte[] scratch = new byte[toRead];
        final int n = in.read(scratch, 0, toRead);

        if (n <= 0) return 0L;



        int off = 0;
        ByteBuffer bb;
        for (WsByteBuffer buffer: buffers){
            if (buffer == null || off>=n) break;
            off += copyInto(buffer, scratch, off, n - off);
        }

        return n;
    }

    private long upgradedImmediateDrain(){
        ensureReadIfManual();
        final NettyServletUpgradeHandler h = ensureUpgradeHandler();
        h.setTCPReadContext(this);
        h.setVC(vc);
        if (!h.containsQueuedData()) return 0L;
        long copied = h.setToBuffer();

        return copied;
    }

    private void ensureBuffersOrJIT(long numBytes, boolean isAsync) {
        if (isAsync && numBytes < 1) {
            throw new IllegalArgumentException("Number of bytes requested to read: " + numBytes
                    + " is less than minimum allowed (1 for asynch)");
        }
        if (!isAsync && numBytes < 0) {
            throw new IllegalArgumentException("Number of bytes requested to read: " + numBytes
                    + " is less than minimum allowed (0 for sync)");
        }
        if (buffers == null || buffers.length == 0 || buffers[0] == null) {
            if (jitAllocateSize > 0) {
                WsByteBuffer buf = ChannelFrameworkFactory.getBufferManager().allocateDirect(jitAllocateSize);
                setBuffer(buf);
                jitAllocateAction = true;
            } else {
                throw new IllegalArgumentException("No buffer(s) provided for reading data into");
            }
        } else {
            jitAllocateAction = false;
        }
        long bytesAvail = 0;
        for (WsByteBuffer b : buffers) {
            if (b == null) break;
            bytesAvail += Math.max(0, b.limit() - b.position());
        }
        if (isAsync) {
            long need = Math.max(1L, numBytes);
            if (bytesAvail < need) {
                throw new IllegalArgumentException("Number of bytes requested: " + numBytes
                        + " exceeds space remaining in the buffers provided: " + bytesAvail);
            }
        } else {
            if (bytesAvail == 0) {
                throw new IllegalArgumentException("Number of bytes requested: " + numBytes
                        + " exceeds space remaining in the buffers provided: 0");
            }
            if (numBytes > 0 && numBytes > bytesAvail) {
                throw new IllegalArgumentException("Number of bytes requested: " + numBytes
                        + " exceeds space remaining in the buffers provided: " + bytesAvail);
            }
        }
    }

    private static int copyInto(WsByteBuffer buf, byte[] src, int off, int len) {
        final java.nio.ByteBuffer bb = buf.getWrappedByteBuffer();
        final int can = Math.min(bb.remaining(), len);
        if (can > 0) {
            bb.put(src, off, can);
            buf.position(bb.position()); 
        }
        return can;
    }

    private static long remaining(WsByteBuffer[] bufs) {
        long tot = 0;
        if (bufs == null)
            return 0;
        for (WsByteBuffer b : bufs) {
            if (b == null)
                break;
            tot += Math.max(0, b.remaining());
        }
        return tot;
    }

    private boolean pushAutoRead() {
        final EventExecutor el = nettyChannel.eventLoop();
        final boolean wasAuto = nettyChannel.config().isAutoRead();
        if (!wasAuto) {
            if (el.inEventLoop()) {
                nettyChannel.config().setAutoRead(true);
                nettyChannel.read();
            } else {
                final CountDownLatch latch = new CountDownLatch(1);
                el.execute(() -> {
                    try {
                        nettyChannel.config().setAutoRead(true);
                        nettyChannel.read();
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return wasAuto;
    }

    private void popAutoRead(boolean wasAuto) {
        if (!wasAuto) {
            final EventExecutor el = nettyChannel.eventLoop();
            if (el.inEventLoop()) {
                nettyChannel.config().setAutoRead(false);
            } else {
                el.execute(() -> nettyChannel.config().setAutoRead(false));
            }
        }
    }

    private void awaitNonUpgradedDataOrTimeout(HttpInputStreamImpl in, NonUpgradedSyncReadSignal sig) throws IOException {
        final Attribute<Runnable> cb = nettyChannel.attr(NettyHttpConstants.ASYNC_READ_CALLBACK);
        final Runnable wakeup = sig.wakeup();

        final Runnable existing = cb.get();
        if (existing != null && existing != wakeup) {
            throw new IllegalStateException("ASYNC_READ_CALLBACK already set (concurrent read?): " + existing);
        }

        final long observed = sig.sequence();

        cb.compareAndSet(null, wakeup);

        try {
            if (in.available() > 0) {
                cb.compareAndSet(wakeup, null);
                return;
            }
        } catch (IOException ignore) {
        }

        // If timeout already fired, don’t block
        if (TimeoutHandler.readOpTimedOut(nettyChannel)) {
            cb.compareAndSet(wakeup, null);
            return;
        }

        try {
            sig.await(observed);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for inbound request data", ie);
        } finally {
            // If we woke due to timeout/close (not inbound data), the callback may still be installed.
            cb.compareAndSet(wakeup, null);
        }
    }


    private long nonUpgradedBlockingReadLoop(HttpInputStreamImpl in, long numBytes,
                                        int effectiveTimeout, NonUpgradedSyncReadSignal sig) throws IOException {

        final byte[] scratch = new byte[8192];
        long delivered = 0L;

        while (true) {

            if (effectiveTimeout > 0 && TimeoutHandler.readOpTimedOut(nettyChannel)) {
                throw new SocketTimeoutException("sync timeout; delivered=" + delivered
                                                + " need=" + numBytes
                                                + " bufRemain=" + remaining(buffers));
            }

            int target = 0;
            for (WsByteBuffer b : buffers) {
                if (b == null) break;
                target += b.remaining();
            }

            if (numBytes > 0) {
                target = (int) Math.min(target, Math.max(0, numBytes - delivered));
            }

            if (target == 0) {
                return delivered;
            }

            final int chunk = Math.min(target, scratch.length);
            final int n = in.read(scratch, 0, chunk);

            if (n > 0) {
                int off = 0;
                for (WsByteBuffer b : buffers) {
                    if (b == null || off >= n) break;
                    off += copyInto(b, scratch, off, n - off);
                }
                delivered += n;

                if (numBytes > 0 && delivered >= numBytes) {
                    return delivered;
                }
                continue;
            }

            if (n == -1) {
                return delivered;
            }

            // No bytes right now; kick the channel if needed
            if (!nettyChannel.config().isAutoRead()) {
                nettyChannel.eventLoop().execute(nettyChannel::read);
            }

            if (effectiveTimeout > 0 && TimeoutHandler.readOpTimedOut(nettyChannel)) {
                throw new SocketTimeoutException("sync timeout; delivered=" + delivered
                                                + " need=" + numBytes
                                                + " bufRemain=" + remaining(buffers));
            }

            if (sig != null) {
                awaitNonUpgradedDataOrTimeout(in, sig);
            } else {
                // Async path keeps the old behavior for now (so we don’t block dispatcher threads)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
    }


    //TODO -> Netty util candidate
    private void assertNotInEventLoop(String method) {
    if (nettyChannel.eventLoop().inEventLoop()) {
        throw new IllegalStateException(method + " must not run on Netty event loop");
    }
}

    //Debug onlly, remove after dev of auto read
    private String logId() {
        return "[ReadCtx ch=" + nettyChannel.id()
               + " ctx=" + System.identityHashCode(this)
               + " vc=" + vc + "]";
    }

    private static final class UpgradedAsyncReadCallback implements TCPReadCompletedCallback {

        private final NettyTCPReadRequestContext owner;
        private final TCPReadCompletedCallback user;
        private final boolean wasAuto;
        private final AtomicReference<TimeoutHandler.ReadOpToken> tokenRef = new AtomicReference<>(null);

        UpgradedAsyncReadCallback(NettyTCPReadRequestContext owner,
                                TCPReadCompletedCallback user,
                                boolean wasAuto) {
            this.owner = owner;
            this.user = user;
            this.wasAuto = wasAuto;
        }

        void armTimeout(Channel ch, int timeoutMs, Runnable onTimeout) {
            tokenRef.set(TimeoutHandler.armReadOp(ch, timeoutMs, onTimeout));
        }

        @Override
        public void complete(VirtualConnection v, TCPReadRequestContext ctx) {
            finish(() -> user.complete(v, ctx));
        }

        @Override
        public void error(VirtualConnection v, TCPReadRequestContext ctx, IOException e) {
            finish(() -> user.error(v, ctx, e));
        }

        private void finish(Runnable dispatch) {
            TimeoutHandler.ReadOpToken tok = tokenRef.getAndSet(null);
            if (tok != null) {
                try { tok.close(); } catch (Throwable ignore) {}
            }

            try {
                ExecutorService exec = HttpDispatcher.getExecutorService();
                if (exec != null) {
                    exec.execute(() -> {
                        try { dispatch.run(); } catch (Throwable ignore) {}
                    });
                } else {
                    // defensive fallback during shutdown
                    try { dispatch.run(); } catch (Throwable ignore) {}
                }
            } finally {
                owner.popAutoRead(wasAuto);
            }
        }
    }

    private static final class NonUpgradedAsyncReadTask implements Runnable {

        private final NettyTCPReadRequestContext owner;
        private final VirtualConnection vc;
        private final TCPReadCompletedCallback user;
        private final long numBytes;
        private final int effectiveTimeout;
        private final boolean wasAuto;

        private final AtomicReference<TimeoutHandler.ReadOpToken> tokenRef = new AtomicReference<>(null);

        NonUpgradedAsyncReadTask(NettyTCPReadRequestContext owner,
                                VirtualConnection vc,
                                TCPReadCompletedCallback user,
                                long numBytes,
                                int effectiveTimeout,
                                boolean wasAuto) {
            this.owner = owner;
            this.vc = vc;
            this.user = user;
            this.numBytes = numBytes;
            this.effectiveTimeout = effectiveTimeout;
            this.wasAuto = wasAuto;
        }

        public void setToken(TimeoutHandler.ReadOpToken tok) {
            tokenRef.set(tok);
        }

        public void kickOnTimeout() {
            Runnable pending = owner.nettyChannel.attr(NettyHttpConstants.ASYNC_READ_CALLBACK).getAndSet(null);
            if (pending != null) {
                // We are already on HttpDispatcher executor (TimeoutHandler runs callbacks there).
                try {
                    pending.run();
                } catch (Throwable ignore) {}
            }
        }

        @Override
        public void run() {
            try {
                HttpInputStreamImpl in = owner.input();

                // No armReadOp here — it was armed at request time.
                owner.nonUpgradedBlockingReadLoop(in, numBytes, effectiveTimeout, null);

                dispatchComplete();

            } catch (IOException ioe) {
                dispatchError(ioe);

            } catch (Throwable t) {
                IOException ioe = (t instanceof IOException)
                        ? (IOException) t
                        : new EOFException(String.valueOf(t));
                dispatchError(ioe);

            } finally {
                TimeoutHandler.ReadOpToken tok = tokenRef.getAndSet(null);
                if (tok != null) {
                    try { tok.close(); } catch (Throwable ignore) {}
                }
                owner.popAutoRead(wasAuto);
            }
        }

        private void dispatchComplete() {
            if (user == null) return;

            ExecutorService exec = HttpDispatcher.getExecutorService();
            if (exec != null) {
                exec.execute(() -> {
                    try { user.complete(vc, owner); } catch (Throwable ignore) {}
                });
            } else {
                try { user.complete(vc, owner); } catch (Throwable ignore) {}
            }
        }

        private void dispatchError(IOException e) {
            if (user == null) return;

            ExecutorService exec = HttpDispatcher.getExecutorService();
            if (exec != null) {
                exec.execute(() -> {
                    try { user.error(vc, owner, e); } catch (Throwable ignore) {}
                });
            } else {
                try { user.error(vc, owner, e); } catch (Throwable ignore) {}
            }
        }
    }

    private static final class NonUpgradedSyncReadSignal {
        private final Channel ch;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition cond = lock.newCondition();
        private long seq = 0L;

        private final Runnable wakeup = this::signal;

        NonUpgradedSyncReadSignal(Channel ch) {
            this.ch = ch;
            ch.closeFuture().addListener(f -> signal());
        }

        private Runnable wakeup() {
            return wakeup;
        }

        private long sequence() {
            lock.lock();
            try {
                return seq;
            } finally {
                lock.unlock();
            }
        }

        private void signal() {
            lock.lock();
            try {
                seq++;
                cond.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void await(long observedSeq) throws InterruptedException {
            lock.lock();
            try {
                while (seq == observedSeq && ch.isActive() && !ch.closeFuture().isDone()) {
                    cond.await();
                }
            } finally {
                lock.unlock();
            }
        }
    }

}