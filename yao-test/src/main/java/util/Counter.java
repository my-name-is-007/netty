/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author wangzongyao on 2020/9/7
 */
public abstract class Counter {

    private static final AtomicInteger counter = new AtomicInteger(1);

    public static int getAndIncrement(){
        return counter.getAndIncrement();
    }

}
