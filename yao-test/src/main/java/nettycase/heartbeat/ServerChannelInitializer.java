/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package nettycase.heartbeat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * @author wangzongyao on 2020/9/6
 */
public class ServerChannelInitializer extends ChannelInitializer {

    /**
     * 加入 netty 提供的 {@link IdleStateHandler}.
     * {@link IdleStateHandler} 是netty 提供的处理空闲状态的处理器
     * 构造器三个参数含义: ↓
     *     long readerIdleTime: 多长时间没有读, 就会发送一个心跳检测包检测是否连接
     *     long writerIdleTime: 多长时间没有写, 就会发送一个心跳检测包检测是否连接
     *     long allIdleTime: 多长时间没有读写, 就会发送一个心跳检测包检测是否连接
     * 同时会触发 {@link IdleStateEvent} 事件(这里是不是同时不太确定, 但肯定会触发事件).
     * 当 此事件 触发后, 会传递给管道 的下一个 Handler 去处理.
     *     所谓的触发, 其实就是调用下一个 Handler 的 userEventTiggered, 在该方法中去处理 {@link IdleStateEvent} 事件.
     */
    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new IdleStateHandler(7000,7000,10, TimeUnit.SECONDS));
        /** 加入一个自定义Handler, 对空闲进一步检测处理. **/
        pipeline.addLast(new ServerHandler());
    }
}
