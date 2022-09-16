/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package callable;

import java.util.concurrent.*;

/**
 * @author wangzongyao on 2020/9/7
 */
@SuppressWarnings("all")
public class Main {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        t1();
//        t2();
    }

    /**
     * 直接使用 Callable.
     */
    public static void t1() throws ExecutionException, InterruptedException {
        SimpleCallable sc = new SimpleCallable(3);
        FutureTask<Integer> ft = new FutureTask<>(sc);
        Thread t1 = new Thread(ft);
        t1.start();
        System.out.println(ft.get());
    }

    /**
     * 通过线程池演示 Callable 用法.
     */
    public static void t2() throws ExecutionException, InterruptedException {
        ExecutorService es = Executors.newFixedThreadPool(3);
        /** 其实这里返回的就是 {@link FutureTask}. **/
        Future<Integer> f = es.submit(new SimpleCallable(6));
        System.out.println(f.get());
    }

}
