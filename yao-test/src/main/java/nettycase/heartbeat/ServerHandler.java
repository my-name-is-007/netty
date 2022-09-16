package nettycase.heartbeat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import static io.netty.handler.timeout.IdleState.*;

public class ServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * @param ctx 上下文
     * @param evt 事件
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (!(evt instanceof IdleStateEvent)) {
            return;
        }
        IdleState eventState = ((IdleStateEvent) evt).state();
        String eventType = null;
        if (READER_IDLE == eventState) {
            eventType = "读空闲";
        } else if (WRITER_IDLE == eventState) {
            eventType = "写空闲";
        } else if (ALL_IDLE == eventState) {
            eventType = "读写空闲";
        }
        System.out.println(ctx.channel().remoteAddress() + "--超时时间--" + eventType);

        //如果发生空闲，我们关闭通道
        // ctx.channel().close();
    }

}
