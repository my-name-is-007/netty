/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;
import static io.netty.buffer.SizeClasses.LOG2_QUANTUM;

/**
 * 内存池子页面类，用于管理小于页面大小的内存块分配
 * 
 * 这个类是 Netty 内存池系统的核心组件之一，专门负责管理小内存块的分配和回收。
 * 它使用位图（bitmap）来跟踪内存块的使用状态，并通过双向链表将相同大小的子页面连接起来。
 * 
 * 主要功能：
 * 1. 将一个页面划分为多个相同大小的小内存块
 * 2. 使用位图跟踪每个内存块的分配状态
 * 3. 提供快速的内存分配和释放操作
 * 4. 通过链表结构管理同类型的子页面
 * 
 * @param <T> 内存类型参数，通常是 byte[] 或 ByteBuffer
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    // 所属的内存块（chunk）
    final PoolChunk<T> chunk;
    
    // 页面大小的位移值，用于快速计算页面大小
    private final int pageShifts;
    
    // 在 chunk 中的运行偏移量
    private final int runOffset;
    
    // 运行区域的大小（字节数）
    private final int runSize;
    
    // 位图数组，用于跟踪内存块的分配状态
    // 每个 bit 代表一个内存块，0 表示空闲，1 表示已分配
    private final long[] bitmap;

    // 双向链表的前一个节点
    PoolSubpage<T> prev;
    
    // 双向链表的下一个节点
    PoolSubpage<T> next;

    // 是否不应该被销毁的标志
    // true: 子页面正在使用中，不能销毁
    // false: 子页面可以被销毁和回收
    boolean doNotDestroy;
    
    // 每个元素（内存块）的大小
    int elemSize;
    
    // 最大元素数量
    private int maxNumElems;
    
    // 位图的有效长度
    private int bitmapLength;
    
    // 下一个可用的内存块索引，用于快速分配
    private int nextAvail;
    
    // 当前可用的内存块数量
    private int numAvail;

    // TODO: 测试在竞争条件下添加填充是否有帮助
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 特殊构造函数，用于创建链表头节点
     * 
     * 链表头节点不代表实际的内存页面，只是作为链表的起始点，
     * 方便进行链表操作（插入、删除等）。
     */
    PoolSubpage() {
        chunk = null;
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
    }

    /**
     * 普通构造函数，创建一个实际的子页面
     * 
     * @param head 链表头节点
     * @param chunk 所属的内存块
     * @param pageShifts 页面大小的位移值
     * @param runOffset 在 chunk 中的偏移量
     * @param runSize 运行区域大小
     * @param elemSize 每个元素的大小
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        this.chunk = chunk;
        this.pageShifts = pageShifts;
        this.runOffset = runOffset;
        this.runSize = runSize;
        this.elemSize = elemSize;
        
        // 计算位图数组大小：runSize / 64 / QUANTUM
        // 每个 long 有 64 位，每个 QUANTUM 单位需要一个位
        bitmap = new long[runSize >>> 6 + LOG2_QUANTUM];

        doNotDestroy = true;
        
        if (elemSize != 0) {
            // 计算最大元素数量
            maxNumElems = numAvail = runSize / elemSize;
            
            // 初始化下一个可用索引
            nextAvail = 0;
            
            // 计算位图的有效长度
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength++;
            }

            // 初始化位图，所有位都设为0（表示空闲）
            for (int i = 0; i < bitmapLength; i++) {
                bitmap[i] = 0;
            }
        }
        
        // 将当前子页面添加到链表中
        addToPool(head);
    }

    /**
     * 分配一个内存块
     * 
     * 从当前子页面中分配一个内存块，返回该内存块的句柄。
     * 句柄包含了定位和识别这个内存块所需的所有信息。
     * 
     * @return 内存块的句柄，如果分配失败则返回 -1
     */
    long allocate() {
        // 检查是否还有可用的内存块，以及子页面是否可用
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获取下一个可用的位图索引
        final int bitmapIdx = getNextAvail();
        
        // 计算在位图数组中的位置
        int q = bitmapIdx >>> 6;  // 除以64，确定在哪个long中
        int r = bitmapIdx & 63;   // 模64，确定在long中的哪一位
        
        // 断言：确保该位当前是0（空闲状态）
        assert (bitmap[q] >>> r & 1) == 0;
        
        // 将对应的位设置为1（标记为已分配）
        bitmap[q] |= 1L << r;

        // 减少可用内存块数量
        if (--numAvail == 0) {
            // 如果没有可用内存块了，从池中移除这个子页面
            removeFromPool();
        }

        // 将位图索引转换为句柄并返回
        return toHandle(bitmapIdx);
    }

    /**
     * 释放一个内存块
     * 
     * 将指定的内存块标记为空闲状态，并更新相关的数据结构。
     * 如果整个子页面都变为空闲状态，可能会被回收。
     * 
     * @param head 链表头节点
     * @param bitmapIdx 要释放的内存块在位图中的索引
     * @return true 如果子页面仍在使用中，false 如果子页面可以被释放
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 如果元素大小为0，直接返回true（特殊情况）
        if (elemSize == 0) {
            return true;
        }
        
        // 计算在位图数组中的位置
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        
        // 断言：确保该位当前是1（已分配状态）
        assert (bitmap[q] >>> r & 1) != 0;
        
        // 将对应的位设置为0（标记为空闲）
        bitmap[q] ^= 1L << r;

        // 设置下一个可用的索引，优化下次分配
        setNextAvail(bitmapIdx);

        // 增加可用内存块数量
        if (numAvail++ == 0) {
            // 如果之前没有可用内存块，现在有了，重新加入池中
            addToPool(head);
            
            /* 当 maxNumElems == 1 时，最大的 numAvail 也是1。
             * 每个这样的 PoolSubpage 在进行释放操作时都会进入这里。
             * 如果它们直接从这里返回 true，那么后面的代码将无法执行，
             * 它们实际上不会被回收。所以只有在 maxNumElems > 1 时才返回 true。 */
            if (maxNumElems > 1) {
                return true;
            }
        }

        // 检查是否所有内存块都空闲了
        if (numAvail != maxNumElems) {
            // 还有内存块在使用中，保持子页面活跃
            return true;
        } else {
            // 子页面未被使用（numAvail == maxNumElems）
            if (prev == next) {
                // 如果这是池中唯一的子页面，不要移除它
                return true;
            }

            // 如果池中还有其他子页面，移除当前子页面
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     * 将当前子页面添加到链表池中
     * 
     * 采用头插法，将新的子页面插入到头节点之后。
     * 
     * @param head 链表头节点
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    /**
     * 从链表池中移除当前子页面
     * 
     * 更新前后节点的指针，将当前节点从链表中断开。
     */
    private void removeFromPool() {
        assert prev != null && next != null;
        
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    /**
     * 设置下一个可用的内存块索引
     * 
     * 这是一个优化手段，记住最近释放的内存块位置，
     * 下次分配时可以直接使用，避免搜索。
     * 
     * @param bitmapIdx 位图索引
     */
    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    /**
     * 获取下一个可用的内存块索引
     * 
     * 首先检查是否有缓存的可用索引，如果没有则进行搜索。
     * 
     * @return 可用的位图索引
     */
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            // 使用缓存的索引，并清除缓存
            this.nextAvail = -1;
            return nextAvail;
        }
        // 没有缓存，需要搜索
        return findNextAvail();
    }

    /**
     * 在位图中查找下一个可用的内存块
     * 
     * 遍历位图数组，寻找第一个不全为1的long值，
     * 然后在该long中找到第一个为0的位。
     * 
     * @return 可用的位图索引，如果没有找到则返回 -1
     */
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        
        for (int i = 0; i < bitmapLength; i++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                // 找到一个不全为1的long，在其中查找空闲位
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * 在指定的long值中查找第一个空闲位
     * 
     * @param i 位图数组的索引
     * @param bits 要搜索的long值
     * @return 可用的位图索引，如果没有找到则返回 -1
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;  // i * 64

        for (int j = 0; j < 64; j++) {
            if ((bits & 1) == 0) {
                // 找到一个空闲位
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    // 超出了有效范围
                    break;
                }
            }
            bits >>>= 1;  // 右移一位，检查下一位
        }
        return -1;
    }

    /**
     * 将位图索引转换为内存句柄
     * 
     * 句柄是一个long值，包含了定位内存块所需的所有信息：
     * - 运行偏移量
     * - 页面数量
     * - 使用标志
     * - 子页面标志
     * - 位图索引
     * 
     * @param bitmapIdx 位图索引
     * @return 内存句柄
     */
    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    /**
     * 返回子页面的字符串表示
     * 
     * 包含运行偏移量、使用情况、大小等信息，便于调试和监控。
     * 
     * @return 描述子页面状态的字符串
     */
    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        
        if (chunk == null) {
            // 这是头节点，不需要同步，因为这些值永远不会改变
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // 不用于创建字符串
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + runOffset + ": not in use)";
        }

        return "(" + runOffset + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + runSize + ", elemSize: " + elemSize + ')';
    }

    /**
     * 获取最大元素数量
     * 
     * @return 子页面能容纳的最大内存块数量
     */
    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // 这是头节点
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    /**
     * 获取当前可用的元素数量
     * 
     * @return 当前可用的内存块数量
     */
    @Override
    public int numAvailable() {
        if (chunk == null) {
            // 这是头节点
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    /**
     * 获取每个元素的大小
     * 
     * @return 每个内存块的字节大小
     */
    @Override
    public int elementSize() {
        if (chunk == null) {
            // 这是头节点
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    /**
     * 获取页面大小
     * 
     * @return 页面的字节大小
     */
    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    /**
     * 销毁子页面
     * 
     * 如果子页面属于某个chunk，则销毁该chunk。
     * 这通常在内存池关闭时调用。
     */
    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
