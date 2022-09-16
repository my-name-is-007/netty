package test;/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

/**
 * @author wangzongyao on 2020/8/23
 */
public class Main {

    public static void main(String[] args) {
//        int coreSize = 3;
//        int maxSize = 10;
//        int keepAlive = 3;
//        ThreadFactory threadFactory = null;
//        RejectedExecutionHandler handler = null;
//        ThreadPoolExecutor pool =
//                new ThreadPoolExecutor(coreSize, maxSize, keepAlive, TimeUnit.SECONDS, new LinkedBlockingQueue(1));
        //回答 加入流程, max值啥的时候, 可以说下 Set<Worker> 这个属性, 可能会给你加分哟 ~
        //因为 这涉及到了源码嘛.
        new Thread(new T()).start();
        new Thread(new T()).start();
        new Thread(new T()).start();




    }

}

class T implements Runnable{

    @Override
    public void run() {
        System.out.println(Thread.currentThread().getName() + "正在执行");
        for (int i = 0; i < 10000; i++) {

        }
        System.out.println(Thread.currentThread().getName() + "马上退出");
    }
}
