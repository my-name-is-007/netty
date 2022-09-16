package nettycase.stickypacket.solve;


import java.nio.charset.Charset;

/**
 * 协议包.
 */
public class MessageProtocol {
    /** 关键. **/
    private int len;
    private byte[] content;

    public static MessageProtocol instance(String mes){
        byte[] content = mes.getBytes(Charset.forName("utf-8"));
        return new MessageProtocol(content.length, content);
    }

    public MessageProtocol(int len, byte[] content) {
        this.len = len;
        this.content = content;
    }

    public int getLen() {
        return len;
    }

    public void setLen(int len) {
        this.len = len;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }
}
