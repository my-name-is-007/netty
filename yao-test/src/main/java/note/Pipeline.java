/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package note;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPipeline;

/**
 * 对 Pipeline的笔记.
 * @author wangzongyao on 2020/9/2
 */
@SuppressWarnings("all")
public class Pipeline {
    /**
     *  ChannelPipeline
     * {@link ChannelPipeline}, 具体实现类 {@link DefaultChannelPipeline}.
     * 内部有 {@link ChannelHandlerContext} 类型的双端链表, 这里 有三个具体类型(我不是说其子类只有三个啊), 分别为: <br />
     *     表头: {@link DefaultChannelPipeline#HeadContext}
     *     表尾: {@link DefaultChannelPipeline#TailContext}
     *     中间结点: {@link DefaultChannelHandlerContext}: 其 handler属性 就是你写的处理器.
     *
     */
}
