package nio.easytest;/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * @author wangzongyao on 2020/8/11
 */
public class NioClient {
    public static Selector selector = null;

    static SocketChannel init(int port) throws IOException {
        SocketChannel client = SocketChannel.open();
        client.configureBlocking(false);
        selector = Selector.open();
        client.register(selector, SelectionKey.OP_READ);
        if(!client.connect(new InetSocketAddress("127.0.0.1", port))){
            //服务器一直没有连接上, 让 客户端 在 此while中 循环.
            while (!client.finishConnect()){ }
        }
        client.write(ByteBuffer.wrap("我是你爸爸".getBytes()));
        return client;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        init(8088);
        while (true){
            //非阻塞的方法, 如果没有事件发生的话, 可以做其他逻辑, 这里没有逻辑, 只是 continue 掉此回合.
            if(selector.selectNow() <= 0) { continue ; }
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey k = keys.next();
                TimeUnit.SECONDS.sleep(3);
                if(k.isAcceptable()){
                    System.out.println("acceptable");
                }else if(k.isReadable()){
                    actionOnReadable(k);
                }else if(k.isWritable()){
                    actionOnWtitable(k);
                }
                keys.remove();
            }
        }
    }

    private static void actionOnWtitable(SelectionKey k) throws IOException {
        System.out.println("writeable");
        SocketChannel channel = (SocketChannel)k.channel();
        channel.write(ByteBuffer.wrap("我是你的第二个爸爸".getBytes()));
        channel.register(selector, SelectionKey.OP_READ);
    }

    private static void actionOnReadable(SelectionKey k) throws IOException {
        System.out.println("readable");
        SocketChannel channel = (SocketChannel)k.channel();
        ByteBuffer bb = ByteBuffer.allocate(1024);
        channel.read(bb);
        System.out.println(new String(bb.array()));
        channel.register(selector, SelectionKey.OP_WRITE);
    }

}
