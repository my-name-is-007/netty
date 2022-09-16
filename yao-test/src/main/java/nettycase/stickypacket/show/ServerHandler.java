package nettycase.stickypacket.show;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.Charset;
import java.util.UUID;

public class ServerHandler extends SimpleChannelInboundHandler<ByteBuf>{
    private static int count;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        byte[] buffer = new byte[msg.readableBytes()];
        msg.readBytes(buffer);
        /** 将buffer转成字符串. **/
        String message = new String(buffer, Charset.forName("utf-8"));
        System.out.println("服务器第"+ (++count) + "次接收到数据: ===" + message + "===");
        /** 服务器回送数据给客户端, 回送一个随机id. **/
        ByteBuf responseByteBuf = Unpooled.copiedBuffer(UUID.randomUUID().toString() + " ", Charset.forName("utf-8"));
        ctx.writeAndFlush(responseByteBuf);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
