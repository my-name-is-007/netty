/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package nettycase.protobuf;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.protobuf.ProtobufDecoder;

/**
 * @author wangzongyao on 2020/9/6
 */
public class ServerChannelInitializer extends ChannelInitializer {
    /**
     * 需要注意的是, 这里 需要  指定对哪种对象进行解码.
     * 客户端可以不用指定, 但是这里需要指定.
     * 其实想想也合理, 因为你要解码, 我肯定要知道你想解成什么样儿的.
     */
    @Override
    protected void initChannel(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("decoder", new ProtobufDecoder(StudentPOJO.Student.getDefaultInstance()));
        pipeline.addLast(new ServerHandler());
    }
}
