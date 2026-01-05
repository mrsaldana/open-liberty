/*******************************************************************************
 * Copyright (c) 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.netty.upgrade;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import com.ibm.ws.netty.upgrade.NettyServletUpgradeHandler;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Tests for NettyServletUpgradeHandler 
 */
@RunWith(Enclosed.class)
public class NettyServletUpgradeHandlerTests {

    private static EmbeddedChannel newChannel(NettyServletUpgradeHandler h, boolean autoRead) {
        EmbeddedChannel ch = new EmbeddedChannel(h);
        ch.config().setAutoRead(autoRead);
        return ch;
    }

    private static void runTasks(EmbeddedChannel ch) {
        ch.runPendingTasks();
        ch.runScheduledPendingTasks();
    }

    private static final class Waiter {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>(null);
        final Thread t;

        Waiter(NettyServletUpgradeHandler h, long waitMillis) {
            t = new Thread(() -> {
                try {
                    h.waitForDataRead(waitMillis);
                } catch (Throwable thr) {
                    error.set(thr);
                } finally {
                    done.countDown();
                }
            }, "UpgradeHandler-waiter");
            t.setDaemon(true);
        }

        void start() {
            t.start();
        }

        boolean awaitDone(long timeoutMs) throws InterruptedException {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private static Object newNettyEvent(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn);

            // Prefer public static INSTANCE if present
            try {
                Field f = c.getField("INSTANCE");
                return f.get(null);
            } catch (NoSuchFieldException ignore) {
            }

            // Else try no-arg ctor
            return c.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            throw new AssertionError("Unable to create event: " + fqcn, t);
        }
    }

    public static class BlockingWaitTests {

        @Test(timeout = 2000)
        public void waitForDataRead_zero_doesNotReturnUntilDataArrives() throws Exception {
            NettyServletUpgradeHandler h = new NettyServletUpgradeHandler(new EmbeddedChannel());
            EmbeddedChannel ch = newChannel(h, false);

            Waiter w = new Waiter(h, 0L);
            w.start();

            // Ensure it doesn't "poll-return" early
            assertFalse("waitForDataRead(0) should block until signaled",
                        w.awaitDone(100));

            // Deliver data to wake it
            ch.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
            runTasks(ch);

            assertTrue("waitForDataRead(0) should return after data arrival",
                       w.awaitDone(500));
            assertNull("No exception expected from waiter thread", w.error.get());

            ch.close();
        }
    }

    public static class ImmediateTimeoutWakeTests {

        @Test(timeout = 2000)
        public void waitForDataRead_zero_returnsWhenImmediateTimeoutTriggered() throws Exception {
            NettyServletUpgradeHandler h = new NettyServletUpgradeHandler(new EmbeddedChannel());
            EmbeddedChannel ch = newChannel(h, false);

            Waiter w = new Waiter(h, 0L);
            w.start();

            assertFalse("waitForDataRead(0) should block until signaled",
                        w.awaitDone(100));

            // immediateTimeout() schedules onto the event loop when called off-loop
            h.immediateTimeout();
            runTasks(ch);

            assertTrue("waitForDataRead(0) should return after immediateTimeout()",
                       w.awaitDone(500));
            assertNull("No exception expected from waiter thread", w.error.get());

            ch.close();
        }
    }

    public static class PeerCloseWakeTests {

        @Test(timeout = 2000)
        public void waitForDataRead_zero_returnsOnInputShutdownEvent() throws Exception {
            NettyServletUpgradeHandler h = new NettyServletUpgradeHandler(new EmbeddedChannel());
            EmbeddedChannel ch = newChannel(h, false);

            Waiter w = new Waiter(h, 0L);
            w.start();

            assertFalse("waitForDataRead(0) should block until signaled",
                        w.awaitDone(100));

            // Simulate half-close / input shutdown (peer closed input)
            Object evt = newNettyEvent("io.netty.channel.socket.ChannelInputShutdownEvent");
            ch.pipeline().fireUserEventTriggered(evt);
            runTasks(ch);

            assertTrue("waitForDataRead(0) should return after input shutdown event",
                       w.awaitDone(500));
            assertNull("No exception expected from waiter thread", w.error.get());
            assertTrue("peerClosedConnection() should be true after input shutdown",
                       h.peerClosedConnection());

            ch.close();
        }
    }

    public static class ChannelCloseWakeTests {

        @Test(timeout = 2000)
        public void waitForDataRead_zero_returnsWhenChannelClosed() throws Exception {
            NettyServletUpgradeHandler h = new NettyServletUpgradeHandler(new EmbeddedChannel());
            EmbeddedChannel ch = newChannel(h, false);

            Waiter w = new Waiter(h, 0L);
            w.start();

            assertFalse("waitForDataRead(0) should block until signaled",
                        w.awaitDone(100));

            // Close should signal waiters (via close + channelInactive signaling)
            ch.close();
            runTasks(ch);

            assertTrue("waitForDataRead(0) should return after channel close",
                       w.awaitDone(500));
            assertNull("No exception expected from waiter thread", w.error.get());

            // channel already closed
        }
    }
}
