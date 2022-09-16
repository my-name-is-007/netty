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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;


/**
 * A nexus to a network socket or a component which is capable of I/O
 * operations such as read, write, connect, and bind.
 * <p>
 * A channel provides a user:
 * <ul>
 * <li>the current state of the channel (e.g. is it open? is it connected?),</li>
 * <li>the {@linkplain ChannelConfig configuration parameters} of the channel (e.g. receive buffer size),</li>
 * <li>the I/O operations that the channel supports (e.g. read, write, connect, and bind), and</li>
 * <li>the {@link ChannelPipeline} which handles all I/O events and requests
 *     associated with the channel.</li>
 * </ul>
 *
 * <h3>All I/O operations are asynchronous.</h3>
 * <p>
 * All I/O operations in Netty are asynchronous.  It means any I/O calls will
 * return immediately with no guarantee that the requested I/O operation has
 * been completed at the end of the call.  Instead, you will be returned with
 * a {@link ChannelFuture} instance which will notify you when the requested I/O
 * operation has succeeded, failed, or canceled.
 *
 * <h3>Channels are hierarchical</h3>
 * <p>
 * A {@link Channel} can have a {@linkplain #parent() parent} depending on
 * how it was created.  For instance, a {@link SocketChannel}, that was accepted
 * by {@link ServerSocketChannel}, will return the {@link ServerSocketChannel}
 * as its parent on {@link #parent()}.
 * <p>
 * The semantics of the hierarchical structure depends on the transport
 * implementation where the {@link Channel} belongs to.  For example, you could
 * write a new {@link Channel} implementation that creates the sub-channels that
 * share one socket connection, as <a href="http://beepcore.org/">BEEP</a> and
 * <a href="https://en.wikipedia.org/wiki/Secure_Shell">SSH</a> do.
 *
 * <h3>Downcast to access transport-specific operations</h3>
 * <p>
 * Some transports exposes additional operations that is specific to the
 * transport.  Down-cast the {@link Channel} to sub-type to invoke such
 * operations.  For example, with the old I/O datagram transport, multicast
 * join / leave operations are provided by {@link DatagramChannel}.
 *
 * <h3>Release resources</h3>
 * <p>
 * It is important to call {@link #close()} or {@link #close(ChannelPromise)} to release all
 * resources once you are done with the {@link Channel}. This ensures all resources are
 * released in a proper way, i.e. filehandles.
 */
public interface Channel extends AttributeMap, ChannelOutboundInvoker, Comparable<Channel> {

    /** 全局唯一标识. **/
    ChannelId id();

    /** 此 Channel 注册到的 {@link EventLoop}. **/
    EventLoop eventLoop();

    /**
     * 父 Channel, 可能为null:
     *     NioServerSocketChannel parent 为null;
     *     SocketChannel parent 为 NioServerSocketChannel.
     */
    Channel parent();

    /** 当前 Channel 配置. **/
    ChannelConfig config();

    /** 如果Channel打开并且稍后可能会激活，则返回true. **/
    boolean isOpen();

    /** 如果Channel处于活动状态并且已连接，则返回true. **/
    boolean isActive();

    /** 如果注册到了 {@link EventLoop} 上则返回 true. **/
    boolean isRegistered();

    /**
     * Return the {@link ChannelMetadata} of the {@link Channel} which describe the nature of the {@link Channel}.
     */
    ChannelMetadata metadata();

    /**
     * Returns the local address where this channel is bound to.  The returned
     * {@link SocketAddress} is supposed to be down-cast into more concrete
     * type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     *
     * @return the local address of this channel.
     *         {@code null} if this channel is not bound.
     */
    SocketAddress localAddress();

    /**
     * 返回此通道连接到的远程地址, 返回的SocketAddress应该向下转换为更具体的类型, 例如InetSocketAddress以检索详细信息.
     *
     * @return 此通道的远程地址, 如果此通道未连接，则为 null.
     *     如果此通道未连接但它可以从任意远程地址接收消息（例如DatagramChannel ，请使用DatagramPacket.recipient()来确定接收消息的来源，因为此方法将返回null
     */
    SocketAddress remoteAddress();

    /**
     * Returns the {@link ChannelFuture} which will be notified when this
     * channel is closed.  This method always returns the same future instance.
     */
    ChannelFuture closeFuture();

    /**
     * Returns {@code true} if and only if the I/O thread will perform the
     * requested write operation immediately.  Any write requests made when
     * this method returns {@code false} are queued until the I/O thread is
     * ready to process the queued write requests.
     */
    boolean isWritable();

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    long bytesBeforeUnwritable();

    /**
     * Get how many bytes must be drained from underlying buffers until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    long bytesBeforeWritable();

    /**
     * Returns an <em>internal-use-only</em> object that provides unsafe operations.
     */
    Unsafe unsafe();

    /** 关联的pipeline. **/
    ChannelPipeline pipeline();

    /** 返回分配的 {@link ByteBufAllocator}, 用于 分配 {@link ByteBuf}s. **/
    ByteBufAllocator alloc();

    @Override
    Channel read();

    @Override
    Channel flush();

    /**
     * Unsafe 是 Netty 到 JDK NIO 的桥梁.
     * 不应该从用户代码中调用的不安全操作。 这些方法仅用于实现实际传输，并且必须从 I/O 线程调用，以下方法除外：
     *
     * <ul>
     *   <li>{@link #localAddress()}</li>
     *   <li>{@link #remoteAddress()}</li>
     *   <li>{@link #closeForcibly()}</li>
     *   <li>{@link #register(EventLoop, ChannelPromise)}</li>
     *   <li>{@link #deregister(ChannelPromise)}</li>
     *   <li>{@link #voidPromise()}</li>
     * </ul>
     */
    interface Unsafe {

        /**
         * Return the assigned {@link RecvByteBufAllocator.Handle} which will be used to allocate {@link ByteBuf}'s when
         * receiving data.
         */
        RecvByteBufAllocator.Handle recvBufAllocHandle();

        /** 当前 Channel 绑定的 {@link SocketAddress}. **/
        SocketAddress localAddress();

        /** 当前Channel连接的地址. **/
        SocketAddress remoteAddress();

        /** 向指定 {@link EventLoop} 注册当前Channel, 注册完成后通知指定的{@link ChannelPromise}. **/
        void register(EventLoop eventLoop, ChannelPromise promise);

        /** 将当前Channel绑定到指定本地地址, 并通知 指定 ChannelPromise. <b>注意是本地地址.</b> **/
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /** 通过本地地址, 连接到指定远程地址, 并通知 ChannelPromise. **/
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * 调度读取操作，以填充ChannelPipeline第一个ChannelInboundHandler的入站缓冲区。
         * 如果已经有一个挂起的读操作，这个方法什么都不做。
         * 写入 缓冲区, 以便 入栈处理器 可以读到 入栈 数据.
         */
        void beginRead();

        /**
         * 向 缓冲区写入数据, 并通知 指定的 ChannelPromise.
         * 这个是写缓冲区, 然后 向外面写, 上面是 写缓冲区, 然后向数据流向内部.
         */
        void write(Object msg, ChannelPromise promise);

        /** 输出 {@link #write(Object, ChannelPromise)} 写的数据. **/
        void flush();

        /**
         * 返回一个特殊的 ChannelPromise, 它可以被重用并传递给Channel.Unsafe的操作.
         * 它永远不会收到成功或错误的通知, 因此它只是将ChannelPromise作为参数但您不想收到通知的操作的占位符.
         * 感觉就是一个通知的空实现而已, 有些类似 NoopLogger、{@link Collections#EMPTY_LIST} 之类.
         */
        ChannelPromise voidPromise();

        /**
         * 返回出栈缓冲区.
         * 返回存储待处理写入请求的 {@link Channel} 的 {@link ChannelOutboundBuffer}。
         */
        ChannelOutboundBuffer outboundBuffer();

        /** ================== 关闭相关 的 API. ================== **/

        /** 取消连接, 并通知 指定 ChannelPromise. **/
        void disconnect(ChannelPromise promise);

        /** 关闭Channel通道, 并通知 ChannelPromise. **/
        void close(ChannelPromise promise);

        /** 立即关闭Channel而不触发任何事件, 可能仅在注册尝试失败时有用. **/
        void closeForcibly();

        /** 将 当前Channel 从 EventLoop 取消注册, 并通知 指定的 ChannelPromise. **/
        void deregister(ChannelPromise promise);
    }
}
