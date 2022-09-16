/*
 * Copyright 2013 The Netty Project
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

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * 任务 的 执行器.
 */
public final class ThreadPerTaskExecutor implements Executor {
    /** 线程工厂, 创建线程. **/
    private final ThreadFactory threadFactory;

    public ThreadPerTaskExecutor(ThreadFactory threadFactory) {
        this.threadFactory = ObjectUtil.checkNotNull(threadFactory, "threadFactory");
    }

    /** 根据 Runnable 创建 Thread, 然后 {@link Thread#start()}. **/
    @Override
    public void execute(Runnable command) {
        threadFactory.newThread(command).start();
    }

    //方便调试而已.
    public DefaultThreadFactory getThreadFactory() { return (DefaultThreadFactory) threadFactory; }
}
