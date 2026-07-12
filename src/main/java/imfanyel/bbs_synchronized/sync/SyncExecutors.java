package imfanyel.bbs_synchronized.sync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon-thread executors used by both sides of the sync pipeline: a small
 * worker pool for hashing and streaming, and a single-thread ordered IO
 * executor that preserves the network-thread arrival order of file chunks.
 */
public class SyncExecutors
{
    public static ExecutorService workers(String name)
    {
        return Executors.newFixedThreadPool(2, daemonFactory(name));
    }

    public static ExecutorService orderedIo(String name)
    {
        return Executors.newSingleThreadExecutor(daemonFactory(name));
    }

    private static ThreadFactory daemonFactory(String name)
    {
        AtomicInteger counter = new AtomicInteger();

        return (runnable) ->
        {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());

            thread.setDaemon(true);

            return thread;
        };
    }
}
