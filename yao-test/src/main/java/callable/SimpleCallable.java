/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package callable;

import java.util.concurrent.Callable;

/**
 * @author wangzongyao on 2020/9/7
 */
public class SimpleCallable implements Callable<Integer> {

    private int num;

    public SimpleCallable(int num) { this.num = num; }

    @Override
    public Integer call() {
        return num;
    }
}
