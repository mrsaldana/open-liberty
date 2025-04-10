package com.ibm.ws.http.netty.inbound;

import java.io.IOException;

import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.bytebuffer.WsByteBufferUtils;

public class WriteTask {
    private final Object lock = new Object();
    private boolean done = false;

    private final WsByteBuffer buffer;
    private final int timeoutMillis;

    private long bytesWritten;
    private IOException exception;

    public WriteTask(WsByteBuffer buffer, int timeoutMillis) {
        this.buffer = buffer;
        this.timeoutMillis = timeoutMillis;
    }

    public WsByteBuffer getBuffer() {
        return buffer;

    }

    public int getTImeoutMillis() {
        return timeoutMillis;
    }

    public void complete(long bytes, IOException e) {
        synchronized (lock) {
            this.done = true;
            this.bytesWritten = bytes;
            this.exception = e;
            lock.notifyAll();
        }
    }

    public long awaitCompletion() throws IOException {
        synchronized (lock) {
            while (!done) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for write", e);
                }
            }
            if (exception != null) {
                throw exception;
            }
            return bytesWritten;
        }
    }
}
