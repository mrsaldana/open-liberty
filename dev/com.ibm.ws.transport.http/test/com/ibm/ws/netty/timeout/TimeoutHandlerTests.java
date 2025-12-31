/*******************************************************************************
 * Copyright (c) 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.netty.timeout;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import com.ibm.ws.http.netty.NettyHttpChannelConfig;
import com.ibm.ws.http.netty.NettyHttpConstants;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import io.openliberty.http.netty.timeout.TimeoutHandler;
import io.openliberty.http.netty.timeout.exception.H2IdleTimeoutException;
import io.openliberty.http.netty.timeout.exception.ReadTimeoutException;
import io.openliberty.http.netty.timeout.exception.TimeoutException;
import io.openliberty.http.options.TcpOption;

@RunWith(Enclosed.class)
public class TimeoutHandlerTests {

    // Shared defaults
    private static final int DEFAULT_INACTIVITY_MS = 0;
    private static final int DEFAULT_READ_MS       = 0;
    private static final int DEFAULT_PERSIST_MS    = 0;
    private static final int DEFAULT_H2_IDLE_MS    = 0;
    private static final boolean DEFAULT_KEEPALIVE = true;

    private static NettyHttpChannelConfig config;

    /**
     * Common config setup used by most tests. Override within specific tests as needed.
     */
    public static void commonSetup() {
        config = mock(NettyHttpChannelConfig.class);

        when(config.get(TcpOption.INACTIVITY_TIMEOUT)).thenReturn(DEFAULT_INACTIVITY_MS);
        when(config.getReadTimeout()).thenReturn(DEFAULT_READ_MS);
        when(config.getPersistTimeout()).thenReturn(DEFAULT_PERSIST_MS);
        when(config.getH2ConnectionIdleTimeout()).thenReturn(DEFAULT_H2_IDLE_MS);
        when(config.isKeepAliveEnabled()).thenReturn(DEFAULT_KEEPALIVE);
    }

    /**
     * Builds a config mock with explicit values
     */
    private static NettyHttpChannelConfig config(int inactivityMs, int readMs, int persistMs, int h2IdleMs, boolean keepAlive) {
        NettyHttpChannelConfig cfg = mock(NettyHttpChannelConfig.class);
        when(cfg.get(TcpOption.INACTIVITY_TIMEOUT)).thenReturn(inactivityMs);
        when(cfg.getReadTimeout()).thenReturn(readMs);
        when(cfg.getPersistTimeout()).thenReturn(persistMs);
        when(cfg.getH2ConnectionIdleTimeout()).thenReturn(h2IdleMs);
        when(cfg.isKeepAliveEnabled()).thenReturn(keepAlive);
        return cfg;
    }

    /**
     * Captures the first exception fired through the pipeline.
     */
    static final class ExceptionCatcher extends ChannelInboundHandlerAdapter {
    final AtomicReference<Throwable> seen = new AtomicReference<>(null);
    final AtomicInteger count = new AtomicInteger(0);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        count.incrementAndGet();
        seen.compareAndSet(null, cause);
    }
}

    /**
     * Advance time and run scheduled + normal tasks.
     */
    private static void advanceAndRun(EmbeddedChannel ch, long millis) {
        ch.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();
    }

    /**
     * Create a channel and set protocol before adding TimeoutHandler.
     */
    private static EmbeddedChannel newChannelWithTimeoutHandler(NettyHttpChannelConfig cfg,
                                                                NettyHttpConstants.ProtocolName proto,
                                                                ExceptionCatcher catcher) {

        EmbeddedChannel ch = new EmbeddedChannel(); 
        if (proto != null) {
            ch.attr(NettyHttpConstants.PROTOCOL).set(proto.protocol);
        }
        ch.pipeline().addLast("timeoutHandler", new TimeoutHandler(cfg));
        ch.pipeline().addLast("exceptionCatcher", catcher);
        return ch;
    }

    private static EmbeddedChannel newChannelForReadOp(ExceptionCatcher catcher) {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter(){}, catcher);
    }


    public static class ExceptionMessageTests {
        @Before
        public void setup() {
            TimeoutHandlerTests.commonSetup();
        }

        @Test
        public void readTimeoutException_formatsMessage() {
            ReadTimeoutException ex = new ReadTimeoutException(5, TimeUnit.SECONDS);
            assertTrue(ex.getMessage().contains("5 seconds"));
            assertTrue(ex instanceof TimeoutException);
        }
    }

    public static class ArmReadOpTests {
        @Before
        public void setup() {
            TimeoutHandlerTests.commonSetup();
        }

        @Test
        public void armReadOp_firesException_and_setsTimedFlag() {
            ExceptionCatcher catcher = new ExceptionCatcher();
            EmbeddedChannel ch = new EmbeddedChannel(catcher);

            // Pass callback=null to avoid coupling this test to HttpDispatcher.getExecutorService()
            TimeoutHandler.ReadOpToken readOp = TimeoutHandler.armReadOp(ch, 25, null);

            advanceAndRun(ch, 30);

            assertTrue("Expected READ_OP_TIMED to be true", TimeoutHandler.readOpTimedOut(ch));
            assertNotNull("Expected an exception to be fired", catcher.seen.get());
            assertTrue("Expected ReadTimeoutException",
                       catcher.seen.get() instanceof ReadTimeoutException);

            readOp.close();
            ch.close();
        }
    }

    public static class ReadOpCancelTests {
        @Before
        public void setup() {
            TimeoutHandlerTests.commonSetup();
        }

        @Test
        public void cancelReadOp_preventsTimeout_and_resetsTimedFlag() {
            ExceptionCatcher catcher = new ExceptionCatcher();
            EmbeddedChannel ch = newChannelForReadOp(catcher);

            TimeoutHandler.armReadOp(ch, 25, null);
            TimeoutHandler.cancelReadOp(ch);

            advanceAndRun(ch, 30);

            assertFalse("Expected READ_OP_TIMED to be false after cancel", TimeoutHandler.readOpTimedOut(ch));
            assertNull("No exception should be fired after cancel", catcher.seen.get());
            assertEquals("No exception expected after cancel", 0, catcher.count.get());

            ch.close();
        }

        @Test
        public void readOpToken_close_preventsTimeout_and_resetsTimedFlag() {
            ExceptionCatcher catcher = new ExceptionCatcher();
            EmbeddedChannel ch = newChannelForReadOp(catcher);

            TimeoutHandler.ReadOpToken token = TimeoutHandler.armReadOp(ch, 25, null);
            token.close();

            advanceAndRun(ch, 30);

            assertFalse("Expected READ_OP_TIMED to be false after token.close()", TimeoutHandler.readOpTimedOut(ch));
            assertNull("No exception should be fired after token.close()", catcher.seen.get());
            assertEquals("No exception expected after token.close()", 0, catcher.count.get());

            ch.close();
        }
    }

    public static class PipelineBehaviorTests {
        @Before
        public void setup() {
            TimeoutHandlerTests.commonSetup();
        }

        @Test
        public void h2IdleTimeout_firesWhenProtocolIsHttp2() {
            NettyHttpChannelConfig cfg = config(0, 0, 0, 25, true);

            ExceptionCatcher catcher = new ExceptionCatcher();
            EmbeddedChannel ch = newChannelWithTimeoutHandler(cfg, NettyHttpConstants.ProtocolName.HTTP2, catcher);

            advanceAndRun(ch, 30);

            assertNotNull("Expected an exception", catcher.seen.get());
            assertTrue("Expected H2IdleTimeoutException", catcher.seen.get() instanceof H2IdleTimeoutException);

            ch.close();
        }
    }

    public static class TcpIdlePhaseTests {
        @Before
        public void setup() {
            TimeoutHandlerTests.commonSetup();
        }

        @Test
        public void tcpIdle_firstRequest_retriesOnce_then_closesChannel_withoutException() {
            // inactivity=10ms, readTimeout=10ms 
            NettyHttpChannelConfig cfg = config(10, 10, 0, 0, true);

            ExceptionCatcher catcher = new ExceptionCatcher();
            EmbeddedChannel ch = newChannelWithTimeoutHandler(cfg, NettyHttpConstants.ProtocolName.HTTP1, catcher);

            // First timeout: should retry, not close yet
            advanceAndRun(ch, 11);
            assertTrue("Channel should still be open after first timeout retry", ch.isOpen());
            assertNull("No exception expected on firstRequest retry", catcher.seen.get());

            // Second timeout: should close channel (firstRequest path closes instead of firing exception)
            advanceAndRun(ch, 11);
            assertFalse("Channel should be closed after second timeout on first request", ch.isOpen());
            assertNull("No exception expected; firstRequest closes channel", catcher.seen.get());
            assertEquals(0, catcher.count.get());

            ch.close();
        }
    }

    
}
