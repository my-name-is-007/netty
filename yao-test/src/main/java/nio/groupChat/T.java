/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package nio.groupChat;

import java.io.IOException;

/**
 * @author wangzongyao on 2020/8/11
 */
public class T extends Thread {

    Client client;

    public T(Client c) {
        client = c;
    }

    @Override
    public void run() {
        while (true) {
            try {
                client.readInfo();
                Thread.sleep(3000);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e2) {
                e2.printStackTrace();
            }
        }
    }
}
