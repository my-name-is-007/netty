/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.test;

import org.junit.Test;

/**
 * @author wangzongyao on 2021/8/21
 */
public class T {

    @Test
    public void test1(){

        byte[] memoryMap = new byte[4096];
        byte[] depthMap = new byte[2048 << 1];

         int memoryMapIndex = 1;
         for (int d = 0; d <= 11; ++ d) {
             int depth = 1 << d;
             for (int p = 0; p < depth; ++ p) {
                 memoryMap[memoryMapIndex] = (byte) d;
                 depthMap[memoryMapIndex] = (byte) d;
                 memoryMapIndex ++;
             }
         }
        System.out.println();
    }

}