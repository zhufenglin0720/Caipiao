package com.zfl.caipiao.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author zfl
 */
public class ThreadUtils {

    private ThreadUtils() {
    }

    private final static class ThreadUtilsHolder {
        private static final ThreadPoolExecutor EXECUTOR =
                new ThreadPoolExecutor(5, 50, 6000,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1000),
                        Executors.defaultThreadFactory(),
                        new ThreadPoolExecutor.DiscardOldestPolicy()
                );
    }

    public static void run(Runnable task){
        ThreadUtilsHolder.EXECUTOR.execute(task);
    }

    public static void shutdown() {
        ThreadUtilsHolder.EXECUTOR.shutdown();
    }
}