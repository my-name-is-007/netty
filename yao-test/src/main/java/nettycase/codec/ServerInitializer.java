package nettycase.codec;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

@SuppressWarnings("all")
public class ServerInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        //入站的handler进行解码 MyByteToLongDecoder
        //pipeline.addLast(new MyByteToLongDecoder2());
        pipeline.addLast(new ByteToLongDecoder());
        //出站的handler进行编码
        pipeline.addLast(new LongToByteEncoder());
        //自定义的handler 处理业务逻辑
        pipeline.addLast(new ServerHandler());
        System.out.println("xx");
    }
}
