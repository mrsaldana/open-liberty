package com.ibm.ws.http.netty.inbound;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.bytebuffer.WsByteBufferUtils;

public class SharedWriter {
    private static final Object INIT_LOCK = new Object();
    private static boolean initialized = false;

    private static BlockingWriteQueue sharedQueue;
    private static CentralWriter centralWriter;
    private static Thread writeThread;

    public static BlockingWriteQueue getOrCreateQueue(Channel nettyChannel) {
        if (!initialized) {
            synchronized (INIT_LOCK) {
                if (!initialized) {
                    sharedQueue = new BlockingWriteQueue();
                    centralWriter = new CentralWriter(sharedQueue, nettyChannel);
                    writeThread = new Thread(centralWriter, "NettyBlockingWriteThread");
                    writeThread.setDaemon(true);
                    writeThread.start();
                    initialized = true;
                }
            }
        }
        return sharedQueue;
    }

    //TODO: hook into framework shutdown afterquiesce to end thread
    public static void shutdown() {
        System.out.println(">>> Entering SharedCentralWriterManager.shutdown()");
        synchronized (INIT_LOCK) {
            if (initialized) {
                System.out.println(">>> Shutting down centralWriter and queue");
                centralWriter.shutdown();
                sharedQueue.signalShutdown();
                writeThread.interrupt();
                try {
                    writeThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                initialized = false;
                System.out.println(">>> Single-writer fully shut down");
            }
        }
    }

    

    
}
