/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package nettycase.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.CharsetUtil;

import java.util.concurrent.TimeUnit;

/**
 *
 * @author wangzongyao on 2020/8/12
 */
public class NettyServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * 读取客户端发送的消息
     * @param ctx 上下文对象, 含有 管道pipeline , 通道channel, 地址
     * @param msg 客户端发送的数据
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        /**
         * 这里定义了三种处理方式
         *   同步处理
         *   异步处理
         *   异步定时处理
         */
        actionSync(ctx,msg);
        //actionAsync(ctx, msg);
        //actionAsyncSchedule(ctx, msg);
    }

    /**
     * 数据读取完毕, 可以在此处向客户端写回数据.
     * writeAndFlush = write + flush.
     * 一般需要对 发送的数据 进行编码.
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.copiedBuffer("客户端你好, 我是你大爷", CharsetUtil.UTF_8));
    }

    /**
     * 处理异常, 需要关闭通道
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }



    private void actionSync(ChannelHandlerContext ctx, Object msg) {
        System.out.println("服务器读取线程 " + Thread.currentThread().getName() + " channle =" + ctx.channel());
        System.out.println("server ctx =" + ctx);
        System.out.println("看看channel 和 pipeline的关系");
        Channel channel = ctx.channel();
        //本质是一个双向链接, 出站入站
        ChannelPipeline pipeline = ctx.pipeline();
        /**
         * 将 msg 转成一个 ByteBuf
         * ByteBuf 是 Netty 提供的，不是 NIO 的 ByteBuffer.
         */
        ByteBuf buf = (ByteBuf) msg;
        System.out.println("客户端发送消息是:" + buf.toString(CharsetUtil.UTF_8));
        System.out.println("客户端地址:" + channel.remoteAddress());
    }

    private void actionAsync(ChannelHandlerContext ctx, Object msg) {
        ctx.channel().eventLoop().execute(() -> {
            try {
                Thread.sleep(5 * 1000);
                ctx.writeAndFlush(Unpooled.copiedBuffer("hello, 客户端~(>^ω^<)喵3", CharsetUtil.UTF_8));
                System.out.println("channel code=" + ctx.channel().hashCode());
            } catch (Exception ex) {
                System.out.println("发生异常" + ex.getMessage());
            }
        });
    }

    private void actionAsyncSchedule(ChannelHandlerContext ctx, Object msg) {
        ctx.channel().eventLoop().schedule(() -> {
            try {
                Thread.sleep(5 * 1000);
                ctx.writeAndFlush(Unpooled.copiedBuffer("hello, 客户端~(>^ω^<)喵4", CharsetUtil.UTF_8));
                System.out.println("channel code=" + ctx.channel().hashCode());
            } catch (Exception ex) {
                System.out.println("发生异常" + ex.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
        System.out.println("异步任务提交完成 ...");
    }
}
