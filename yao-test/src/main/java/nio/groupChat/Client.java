package nio.groupChat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;

public class Client {

    private Selector selector;
    private SocketChannel socketChannel;
    private String username;

    public Client(String serverHost, int serverPort) throws IOException {
        selector = Selector.open();
        socketChannel = SocketChannel.open(new InetSocketAddress(serverHost, serverPort));
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_READ);
        username = socketChannel.getLocalAddress().toString().substring(1);
    }

    /** 向服务器发送消息. **/
    public void sendInfo(String info) throws IOException {
        socketChannel.write(ByteBuffer.wrap(info.getBytes()));
    }

    //读取从服务器端回复的消息
    public void readInfo() throws IOException {
        if(selector.select() <= 0){
            return ;
        }
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            if(key.isReadable()) {
               SocketChannel sc = (SocketChannel) key.channel();
                ByteBuffer buffer = ByteBuffer.allocate(1024);
                sc.read(buffer);
                String msg = new String(buffer.array());
                System.out.println(msg.trim());
            }
            iterator.remove();
        }
    }

    public static void main(String[] args) throws Exception {
        //启动我们客户端
        Client client = new Client("127.0.0.1", 80);
        //每3秒, 从服务器读取数据.
        new T(client).start();
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String s = scanner.nextLine();
            client.sendInfo(s);
        }
    }
}
