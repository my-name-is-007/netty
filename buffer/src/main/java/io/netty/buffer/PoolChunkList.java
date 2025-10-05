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

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

import java.nio.ByteBuffer;

/**
 * 内存池块链表类
 * 
 * 这个类是 Netty 内存池管理系统的核心组件之一，用于管理具有相似使用率的内存块（PoolChunk）。
 * 
 * 主要功能：
 * 1. 维护一个双向链表，存储使用率在 [minUsage, maxUsage] 范围内的 PoolChunk 对象
 * 2. 当内存块的使用率发生变化时，自动将其移动到合适的 PoolChunkList 中
 * 3. 提供高效的内存分配和释放操作
 * 4. 通过使用率分组管理，减少内存碎片，提高内存利用率
 * 
 * 设计思想：
 * - 将内存块按使用率分组，避免在低使用率的块中分配小内存，减少碎片
 * - 使用双向链表结构，支持快速插入、删除和移动操作
 * - 通过阈值机制，实现内存块在不同链表间的自动迁移
 */
final class PoolChunkList<T> implements PoolChunkListMetric {
    
    // 空的度量指标迭代器，用于没有内存块时返回
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    
    // 所属的内存池竞技场
    private final PoolArena<T> arena;
    
    // 指向下一个 PoolChunkList 的引用（使用率更高的链表）
    private final PoolChunkList<T> nextList;
    
    // 最小使用率阈值（百分比）
    private final int minUsage;
    
    // 最大使用率阈值（百分比）
    private final int maxUsage;
    
    // 此链表中内存块能分配的最大容量
    private final int maxCapacity;
    
    // 链表头节点，指向第一个内存块
    private PoolChunk<T> head;
    
    // 空闲字节数的最小阈值，用于判断是否需要移动到下一个链表
    private final int freeMinThreshold;
    
    // 空闲字节数的最大阈值，用于判断是否需要移动到上一个链表
    private final int freeMaxThreshold;

    // 这个字段只在 PoolArena 构造函数中创建链表时设置一次
    private PoolChunkList<T> prevList;

    // TODO: 测试在高并发情况下添加填充是否有助于性能
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 构造函数
     * 
     * @param arena 所属的内存池竞技场
     * @param nextList 下一个 PoolChunkList（使用率更高的链表）
     * @param minUsage 最小使用率阈值（0-100）
     * @param maxUsage 最大使用率阈值（0-100）
     * @param chunkSize 内存块大小
     */
    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);

        // 阈值的计算与 PoolChunk.usage() 逻辑保持一致：
        // 
        // 1) 基本逻辑：usage() = 100 - freeBytes * 100L / chunkSize
        //    例如：(usage() >= maxUsage) 条件可以转换为：
        //      100 - freeBytes * 100L / chunkSize >= maxUsage
        //      freeBytes <= chunkSize * (100 - maxUsage) / 100
        //      设 freeMinThreshold = chunkSize * (100 - maxUsage) / 100，则 freeBytes <= freeMinThreshold
        //
        // 2) usage() 返回 int 值，计算过程中有向下取整的舍入操作，
        //    为了保持一致性，绝对阈值需要进行"舍入步长"的偏移：
        //       freeBytes * 100 / chunkSize < 1
        //       这个条件可以转换为：freeBytes < 1 * chunkSize / 100
        //    这就是为什么我们有 + 0.99999999 的偏移。举例说明为什么不能只用 +1 偏移：
        //       freeBytes = 16777216 == freeMaxThreshold: 16777216, usage = 0 < minUsage: 1, chunkSize: 16777216
        //    同时，当 (maxUsage == 100) 和 (minUsage == 100) 时，我们希望阈值为零。
        //
        freeMinThreshold = (maxUsage == 100) ? 0 : (int) (chunkSize * (100.0 - maxUsage + 0.99999999) / 100L);
        freeMaxThreshold = (minUsage == 100) ? 0 : (int) (chunkSize * (100.0 - minUsage + 0.99999999) / 100L);
    }

    /**
     * 计算最大容量
     * 
     * 计算在给定 minUsage 和 maxUsage 设置下，属于此 PoolChunkList 的 PoolChunk 中
     * 可能分配的缓冲区的最大容量。
     * 
     * @param minUsage 最小使用率
     * @param chunkSize 内存块大小
     * @return 最大可分配容量
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // 如果最小使用率是 100%，则无法从此链表中分配任何内存
            return 0;
        }

        // 计算此 PoolChunkList 中 PoolChunk 可以分配的最大字节数
        //
        // 举例说明：
        // - 如果 PoolChunkList 的 minUsage == 25，我们最多只能分配 chunkSize 的 75%，
        //   因为这是此 PoolChunkList 中任何 PoolChunk 的最大可用量。
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    /**
     * 设置前一个链表的引用
     * 
     * @param prevList 前一个 PoolChunkList
     */
    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    /**
     * 分配内存
     * 
     * 尝试从此链表中的内存块分配指定大小的内存。
     * 
     * @param buf 要初始化的 PooledByteBuf
     * @param reqCapacity 请求的容量
     * @param sizeIdx 大小索引
     * @param threadCache 线程缓存
     * @return 如果分配成功返回 true，否则返回 false
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        int normCapacity = arena.sizeIdx2size(sizeIdx);
        if (normCapacity > maxCapacity) {
            // 要么此 PoolChunkList 为空，要么请求的容量大于此 PoolChunkList 中
            // 包含的 PoolChunk 可以处理的容量
            return false;
        }

        // 遍历链表中的每个内存块，尝试分配内存
        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            if (cur.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
                // 分配成功后，检查是否需要将此内存块移动到下一个链表
                if (cur.freeBytes <= freeMinThreshold) {
                    remove(cur);
                    nextList.add(cur);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 释放内存
     * 
     * 释放指定内存块中的内存，并根据使用率变化决定是否移动内存块。
     * 
     * @param chunk 内存块
     * @param handle 内存句柄
     * @param normCapacity 标准化容量
     * @param nioBuffer NIO 缓冲区
     * @return 如果内存块仍然有效返回 true，如果需要销毁返回 false
     */
    boolean free(PoolChunk<T> chunk, long handle, int normCapacity, ByteBuffer nioBuffer) {
        chunk.free(handle, normCapacity, nioBuffer);
        if (chunk.freeBytes > freeMaxThreshold) {
            remove(chunk);
            // 将 PoolChunk 向下移动到 PoolChunkList 链表中
            return move0(chunk);
        }
        return true;
    }

    /**
     * 移动内存块
     * 
     * 将内存块移动到合适的链表中。
     * 
     * @param chunk 要移动的内存块
     * @return 如果移动成功返回 true，如果需要销毁返回 false
     */
    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.freeBytes > freeMaxThreshold) {
            // 将 PoolChunk 向下移动到 PoolChunkList 链表中
            return move0(chunk);
        }

        // PoolChunk 适合此 PoolChunkList，将其添加到这里
        add0(chunk);
        return true;
    }

    /**
     * 移动内存块的内部实现
     * 
     * 将 PoolChunk 向下移动到 PoolChunkList 链表中，使其最终位于正确的 PoolChunkList 中，
     * 该链表具有与 PoolChunk.usage() 相对应的正确 minUsage / maxUsage。
     * 
     * @param chunk 要移动的内存块
     * @return 如果移动成功返回 true，如果需要销毁返回 false
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // 没有前一个 PoolChunkList，返回 false 将导致 PoolChunk 被销毁，
            // 并释放与 PoolChunk 关联的所有内存
            assert chunk.usage() == 0;
            return false;
        }
        return prevList.move(chunk);
    }

    /**
     * 添加内存块
     * 
     * 根据内存块的空闲字节数决定添加到当前链表还是下一个链表。
     * 
     * @param chunk 要添加的内存块
     */
    void add(PoolChunk<T> chunk) {
        if (chunk.freeBytes <= freeMinThreshold) {
            nextList.add(chunk);
            return;
        }
        add0(chunk);
    }

    /**
     * 将内存块添加到此链表
     * 
     * 将 PoolChunk 添加到此 PoolChunkList 的头部。
     * 
     * @param chunk 要添加的内存块
     */
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        if (head == null) {
            // 链表为空，直接设置为头节点
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            // 插入到链表头部
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    /**
     * 从链表中移除内存块
     * 
     * @param cur 要移除的内存块
     */
    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            // 移除头节点
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            // 移除中间或尾部节点
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    /**
     * 获取最小使用率
     * 
     * @return 最小使用率（至少为1）
     */
    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    /**
     * 获取最大使用率
     * 
     * @return 最大使用率（最多为100）
     */
    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    /**
     * 确保最小使用率至少为1
     * 
     * @param value 原始值
     * @return 调整后的值（至少为1）
     */
    private static int minUsage0(int value) {
        return max(1, value);
    }

    /**
     * 获取内存块度量指标的迭代器
     * 
     * @return 内存块度量指标的迭代器
     */
    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    /**
     * 转换为字符串表示
     * 
     * @return 包含所有内存块信息的字符串
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    /**
     * 销毁链表
     * 
     * 销毁链表中的所有内存块并释放相关资源。
     * 
     * @param arena 内存池竞技场
     */
    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
