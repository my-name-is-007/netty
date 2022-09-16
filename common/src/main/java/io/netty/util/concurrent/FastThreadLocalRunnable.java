/*
* Copyright 2017 The Netty Project
*
* The Netty Project licenses this file to you under the Apache License,
* version 2.0 (the "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at:
*
*   https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;

/**
 * 将 普通 Runnable 作为自己属性, run 还是 靠 传进来的那个.
 * 只是 添加 了 FastThreadLocal 的 处理.
 */
final class FastThreadLocalRunnable implements Runnable {

    /** 实际运行的Runnanle. **/
    private final Runnable runnable;

    private FastThreadLocalRunnable(Runnable runnable) {
        this.runnable = ObjectUtil.checkNotNull(runnable, "runnable");
    }

    /** 这里配合着 {@link FastThreadLocal}, 后面会说这个类, 早期笔记中对此类也有过记载. **/
    @Override
    public void run() {
        try {
            runnable.run();
        } finally {
            FastThreadLocal.removeAll();
        }
    }

    /** 如果是 FastThreadLocalRunnable, 直接返回; 否则 包装下(所谓的包装其实仅仅是将其作为自己属性). **/
    static Runnable wrap(Runnable runnable) {
        return runnable instanceof FastThreadLocalRunnable ? runnable : new FastThreadLocalRunnable(runnable);
    }
}
