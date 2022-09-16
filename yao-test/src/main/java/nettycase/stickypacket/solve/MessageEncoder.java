package nettycase.stickypacket.solve;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class MessageEncoder extends MessageToByteEncoder<MessageProtocol> {
    @Override
    protected void encode(ChannelHandlerContext ctx, MessageProtocol msg, ByteBuf out) {
        System.out.println("消息 <===出站 编码器===> 被调用");
        out.writeInt(msg.getLen());
        out.writeBytes(msg.getContent());
    }
}
