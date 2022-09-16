/*
 * Copyright 2012 The Netty Project
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

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 *
 * 这里开始有些事件轮询的意思了.
 */
public interface EventExecutor extends EventExecutorGroup {

    /** 返回 自身. **/
    @Override
    EventExecutor next();

    /** 所属 事件执行组. **/
    EventExecutorGroup parent();

    /** 使用{@link Thread#currentThread()} 作为参数调用 {@link #inEventLoop(Thread)}. **/
    boolean inEventLoop();

    /** 如果给定的 Thread 在事件循环中执行 则返回true, 否则返回false . **/
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     */
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     */
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * 创建一个新的Future已经标记为成功, 所以Future.isSuccess()将返回true.
     * 所有添加到其中的 FutureListener 都会直接收到通知.
     * 此外，每次调用阻塞方法都会返回而不会阻塞。
     */
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
