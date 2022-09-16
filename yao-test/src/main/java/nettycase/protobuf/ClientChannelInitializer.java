/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package nettycase.protobuf;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.protobuf.ProtobufEncoder;

/**
 * @author wangzongyao on 2020/9/6
 */
public class ClientChannelInitializer extends ChannelInitializer {
    /**
     * 注意: 这里不用你指定要编码的类.
     * 因为你是发送的, 发送的啥类型, 啥对象, 在你发送时, 就一定明确的指定了.
     * 说白了, 就是在 你自己写的Handler里面, writeAndFlush时, 你指定的啥, 那就是啥.
     */
    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("encoder", new ProtobufEncoder());
        pipeline.addLast(new ClientHandler());
    }
}
