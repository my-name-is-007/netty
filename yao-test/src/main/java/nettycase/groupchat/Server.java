package nettycase.groupchat;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class Server {

    /** 监听端口. **/
    private int port;

    public Server(int port) {
        this.port = port;
    }

    /** 编写run方法, 处理客户端的请求. **/
    public void run() throws  Exception{
        /** 创建两个线程组. **/
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        /** 8个NioEventLoop. **/
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ServerChannelInitializer());

            System.out.println("netty 服务器启动");
            ChannelFuture channelFuture = b.bind(port).sync();
            /** 监听关闭. **/
            channelFuture.channel().closeFuture().sync();
        }finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {

        new Server(7000).run();
    }
}
