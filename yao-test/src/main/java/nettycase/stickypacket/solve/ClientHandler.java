package nettycase.stickypacket.solve;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.Charset;

public class ClientHandler extends SimpleChannelInboundHandler<MessageProtocol> {

    private static int count;

    private int sendTimes = 5;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String mes = "今天天气冷，吃火锅";
        for(int i = 1; i<= sendTimes; i++) {
            MessageProtocol instance = MessageProtocol.instance(mes + i);
            ctx.writeAndFlush(instance);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageProtocol msg) {
        int len = msg.getLen();
        byte[] content = msg.getContent();

        System.out.println("客户端收到第" + (++count)+ "条消息, 长度=" + len + ", 内容=" + new String(content, Charset.forName("utf-8")));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("异常消息=" + cause.getMessage());
        ctx.close();
    }
}
