package nettycase.groupchat;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Scanner;

public class Client {

    private final String serverHost;
    private final int serverPort;

    public Client(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public void run() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ClientChannelInitializer());

            ChannelFuture channelFuture = bootstrap.connect(serverHost, serverPort).sync();
            /** 得到channel. **/
            Channel channel = channelFuture.channel();
            System.out.println("-------" + channel.localAddress() + "--------");
            /** 客户端需要输入信息. **/
            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String msg = scanner.nextLine();
                /** 通过channel, 发送到服务器端. **/
                channel.writeAndFlush(msg + "\r\n");
            }
        } finally {
            group.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        new Client("127.0.0.1", 7000).run();
    }
}
