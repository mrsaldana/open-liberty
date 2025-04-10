package com.ibm.ws.http.netty.inbound;

import java.util.LinkedList;
import java.util.Queue;

public class BlockingWriteQueue {
    private final Object lock = new Object();
    private final Queue<WriteTask> tasks = new LinkedList<>();
    private volatile boolean shuttingDown = false;

    public void addTask(WriteTask task) {
        synchronized (lock) {
            tasks.offer(task);
            lock.notify();
        }
    }

    public void signalShutdown(){
        synchronized(lock){
            shuttingDown = true;
            lock.notifyAll();
        }
    }

    public WriteTask takeTask() throws InterruptedException {
        synchronized (lock) {
            while (tasks.isEmpty() && !shuttingDown) {
                lock.wait();
            }
            if(shuttingDown && tasks.isEmpty()){
                return null;
            }
            return tasks.poll();
        }
    }
}
