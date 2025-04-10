package com.ibm.ws.http.netty.inbound;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.bytebuffer.WsByteBufferUtils;

public class CentralWriter implements Runnable {
    private final BlockingWriteQueue queue;
    private final Channel nettyChannel;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public CentralWriter(BlockingWriteQueue queue, Channel nettyChannel) {
        this.queue = queue;
        this.nettyChannel = nettyChannel;
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            WriteTask task;
            try {
                task = queue.takeTask();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (task == null) {
                break;
            }

            long bytes = 0;
            IOException failure = null;
            try {
                bytes = doBlockingWrite(task.getBuffer(), task.getTImeoutMillis());
            } catch (IOException e) {
                failure = e;
            }
            task.complete(bytes, failure);
        }
    }

    private long doBlockingWrite(WsByteBuffer buf, int timeoutMillis) throws IOException {
        if (buf == null || !buf.hasRemaining()) {
            return 0;
        }

        byte[] data = WsByteBufferUtils.asByteArray(buf);
        ByteBuf nettyBuf = Unpooled.wrappedBuffer(data);

        ChannelFuture writeF = nettyChannel.write(nettyBuf);
        ChannelFuture flushF = nettyChannel.writeAndFlush(Unpooled.EMPTY_BUFFER);

        try {
            if (timeoutMillis > 0) {
                boolean done = flushF.await(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!done) {
                    flushF.cancel(true);
                    throw new IOException("Write op timed out");
                }
            } else {
                flushF.sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for write", e);
        }

        if (!flushF.isSuccess()) {
            throw new IOException("Write op failed", flushF.cause());
        }
        return buf.remaining();
    }

    public void shutdown() {
        running.set(false);
        queue.signalShutdown();
    }
}
