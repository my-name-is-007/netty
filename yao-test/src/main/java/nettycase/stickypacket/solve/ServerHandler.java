package nettycase.stickypacket.solve;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.Charset;
import java.util.UUID;

@SuppressWarnings("all")
public class ServerHandler extends SimpleChannelInboundHandler<MessageProtocol>{
    private static int count;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageProtocol msg) throws Exception {
        System.out.println("服务器第 " + (++count) + " 次收到数据, 长度="+msg.getLen()+", 内容="+new String(msg.getContent(), Charset.forName("utf-8")));

        //构建 协议包, 回复消息
        MessageProtocol messageProtocol = MessageProtocol.instance(UUID.randomUUID().toString());
        ctx.writeAndFlush(messageProtocol);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

}
