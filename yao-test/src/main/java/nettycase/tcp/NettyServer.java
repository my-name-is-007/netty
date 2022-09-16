/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package nettycase.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * @author wangzongyao on 2020/8/12
 */
public class NettyServer {

    public static void main(String[] args) throws Exception {
        /**
         * 创建两个线程组 bossGroup 和 workerGroup
         * bossGroup 只是处理连接请求 , 真正的和客户端业务处理，会交给 workerGroup完成
         * 两个都是无限循环
         * bossGroup 和 workerGroup 含有的子线程(NioEventLoop)的个数, 默认实际 cpu核数 * 2
         */
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            //服务端启动对象
            ServerBootstrap bootstrap = new ServerBootstrap();
            /**
             * 设置两个线程组
             * 使用NioSocketChannel 作为服务器的通道实现
             * 设置线程队列的连接个数
             * 设置保持活动连接状态
             * 创建一个通道初始化对象
             */
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        /** 向 pipeline 添加处理器. **/
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            /**
                             * 可以使用一个集合管理 SocketChannel, 推送消息时,
                             * 将业务加入到各个channel 对应的 NIOEventLoop 的 taskQueue 或者 scheduleTaskQueue
                             */
                            System.out.println("客户socketchannel hashcode=" + ch.hashCode());
                            ch.pipeline().addLast(new NettyServerHandler());
                        }
                    });

            System.out.println("服务器 启动了 ...");

            //绑定端口, 启动服务器, 并且同步
            ChannelFuture cf = bootstrap.bind(6668).sync();
            //给cf 注册监听器，监控我们关心的事件
            cf.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (cf.isSuccess()) {
                        System.out.println("监听端口 6668 成功");
                    } else {
                        System.out.println("监听端口 6668 失败");
                    }
                }
            });
            //对关闭通道进行监听
            cf.channel().closeFuture().sync();
        }finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
