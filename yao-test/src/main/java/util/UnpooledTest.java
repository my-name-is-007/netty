/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package util;

import java.util.ArrayList;

/**
 * 演示 Netty的 Unpooled 使用.
 * @author wangzongyao on 2020/9/2
 */
public class UnpooledTest {

    public static void main(String[] args) {
        ArrayList<String> al = new ArrayList<>();

        for (String s : al) {
            al.add("aaa");
            al.remove(s);
        }

    }

}
