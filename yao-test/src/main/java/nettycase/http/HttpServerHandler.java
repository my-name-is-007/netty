package nettycase.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.net.URI;

/**
 * SimpleChannelInboundHandler<I> extends ChannelInboundHandlerAdapter.
 * 这里的Handler会创建多次, 还是会创建一次, 和41行代码要不要返回有关, 我新开一个浏览器发请求也有关.
 * 具体的可以去看源码, 只看这些表象去总结, 没一点儿意义, 更没意思.
 */
public class HttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {

    static int createdTimes = 0;

    public HttpServerHandler() {
        System.out.println("被创建" + (++createdTimes));
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
    }

    /**
     * 读取客户端数据.
     *
     * @param msg HttpObject 客户端和服务器端相互通讯的数据被封装成 HttpObject
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        System.out.println("当前handler=" + ctx.handler());
        //判断 msg 是不是 httpRequest请求
        if (!(msg instanceof HttpRequest)) {
            return;
        }
        System.out.println("msg 类型=" + msg.getClass() + ", 客户端地址" + ctx.channel().remoteAddress());
        HttpRequest httpRequest = (HttpRequest) msg;
        //获取uri, 过滤指定的资源
        if ("/favicon.ico2".equals(new URI(httpRequest.uri()).getPath())) {
            System.out.println("请求了 favicon.ico, 不做响应\n\n");
            return ;
        }
        ByteBuf content = Unpooled.copiedBuffer("我是你爸爸服务器", CharsetUtil.UTF_8);
        //构造 Http响应: httpResponse.
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        //将 response 写回.
        ctx.write(response);
        ctx.flush();
        //ctx.writeAndFlush(response);

    }

}
