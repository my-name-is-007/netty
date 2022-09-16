package nettycase.protobuf;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 在这里, 我们只是将 {@link StudentPOJO} 这一个类进行了编解码,
 * 假如说, 我们要再对另一个类: Student2 进行编解码进行传输, 要怎么做呢?
 * 要再编写一个 .proto文件, 然后编写对应的Handler吗? 这样可以, 但是太麻烦了, 这点可以看 protobuf2 这个包.
 */
public class Server {
    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ServerChannelInitializer());

            System.out.println(".....服务器 is ready...");
            ChannelFuture cf = bootstrap.bind(6668).sync();

            cf.addListener((ChannelFutureListener) future -> {
                if (cf.isSuccess()) {
                    System.out.println("监听端口 6668 成功");
                } else {
                    System.out.println("监听端口 6668 失败");
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
