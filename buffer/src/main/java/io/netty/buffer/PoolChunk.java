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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

/**
 * 内存池块（PoolChunk）- Netty内存池管理的核心组件
 * 
 * 这个类负责管理一大块连续的内存区域，并将其分割成更小的内存段供ByteBuf使用。
 * 它实现了高效的内存分配和回收算法，减少内存碎片和提高性能。
 *
 * 重要概念说明：
 * 
 * > page（页）  - 可分配的最小内存单元
 * 
 * > run（运行块） - 由一个或多个连续页组成的内存块
 * 
 * > chunk（块） - 由多个运行块组成的大内存区域
 * 
 * > 在此代码中：chunkSize = maxPages * pageSize
 *
 * 工作原理：
 * 
 * 首先分配一个大小为chunkSize的字节数组作为内存池。
 * 
 * 当需要创建指定大小的ByteBuf时，在字节数组中搜索第一个有足够空间的位置，
 * 
 * 返回一个编码了偏移信息的长整型句柄（handle），该内存段被标记为已使用，
 * 
 * 确保只被一个ByteBuf使用。
 *
 * 为了简化处理，所有大小都根据 {@link PoolArena#size2SizeIdx(int)} 方法进行标准化。
 * 
 * 这确保当请求大小 > pageSize 的内存段时，标准化容量等于 {@link SizeClasses} 中的下一个最近大小。
 *
 * 内存块布局：
 *
 *     /-----------------\
 *     | run（运行块）    |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run（运行块）    |
 *     |                 |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | 未分配（已释放） |
 *     |-----------------|
 *     | subpage（子页）  |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | 未分配（已释放） |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 * 句柄（handle）格式：
 * 
 * 句柄是一个长整型数字，运行块的位布局如下：
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset（块中的页偏移），15位
 * 
 * s: size（此运行块的页数），15位
 * 
 * u: isUsed?（是否已使用），1位
 * 
 * e: isSubpage?（是否为子页），1位
 * 
 * b: bitmapIdx（子页的位图索引），如果不是子页则为0，32位
 *
 * runsAvailMap（可用运行块映射）：
 * 
 * 管理所有运行块（已使用和未使用）的映射表。
 * 
 * 对于每个运行块，第一个runOffset和最后一个runOffset存储在runsAvailMap中。
 * 
 * key: runOffset（运行块偏移）
 * 
 * value: handle（句柄）
 *
 * runsAvail（可用运行块数组）：
 * 
 * {@link PriorityQueue} 的数组。
 * 
 * 每个队列管理相同大小的运行块。
 * 
 * 运行块按偏移排序，确保总是分配偏移较小的运行块。
 *
 * 算法说明：
 * 
 * 在分配运行块时，更新runsAvailMap和runsAvail中存储的值以维护属性。
 *
 * 初始化：
 * 
 * 开始时存储初始运行块，即整个块。
 * 
 * 初始运行块：
 * 
 * runOffset = 0
 * 
 * size = chunkSize
 * 
 * isUsed = no
 * 
 * isSubpage = no
 * 
 * bitmapIdx = 0
 *
 * 算法：[allocateRun(size)]
 * 
 * 1) 根据大小在runsAvails中找到第一个可用运行块
 * 
 * 2) 如果运行块的页数大于请求的页数，则分割它，并保存尾部运行块供以后使用
 *
 * 算法：[allocateSubpage(size)]
 * 
 * 1) 根据大小找到一个未满的子页。
 * 
 *    如果已存在则直接返回，否则分配新的PoolSubpage并调用init()
 * 
 *    注意：当我们init()时，此子页对象被添加到PoolArena中的subpagesPool
 * 
 * 2) 调用subpage.allocate()
 *
 * 算法：[free(handle, length, nioBuffer)]
 * 
 * 1) 如果是子页，将slab返回到此子页
 * 
 * 2) 如果子页未使用或它是运行块，则开始释放此运行块
 * 
 * 3) 合并连续的可用运行块
 * 
 * 4) 保存合并后的运行块
 */
final class PoolChunk<T> implements PoolChunkMetric {
    
    // 句柄中各字段的位长度定义
    private static final int SIZE_BIT_LENGTH = 15;          // 大小字段位长度
    
    private static final int INUSED_BIT_LENGTH = 1;         // 使用状态字段位长度
    
    private static final int SUBPAGE_BIT_LENGTH = 1;        // 子页标志字段位长度
    
    private static final int BITMAP_IDX_BIT_LENGTH = 32;    // 位图索引字段位长度

    // 句柄中各字段的位移位置定义
    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;                      // 子页标志位移
    
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;         // 使用状态位移
    
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;                // 大小字段位移
    
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;               // 运行块偏移位移

    // 核心组件引用
    final PoolArena<T> arena;           // 所属的内存池竞技场
    
    final Object base;                  // 内存基础对象（用于unsafe操作）
    
    final T memory;                     // 实际的内存对象（ByteBuffer或byte[]）
    
    final boolean unpooled;             // 是否为非池化内存块

    /**
     * 存储每个可用运行块的第一页和最后一页
     * 
     * 这是一个映射表，用于快速查找指定偏移位置的运行块信息
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * 管理所有可用运行块的优先队列数组
     * 
     * 每个队列管理相同大小的运行块，按偏移量排序
     */
    private final LongPriorityQueue[] runsAvail;

    /**
     * 管理此块中所有子页的数组
     * 
     * 索引对应页偏移，值为对应的PoolSubpage对象
     */
    private final PoolSubpage<T>[] subpages;

    // 内存块配置参数
    private final int pageSize;         // 页大小
    
    private final int pageShifts;       // 页大小的位移值（用于快速计算）
    
    private final int chunkSize;        // 块总大小

    /**
     * ByteBuffer缓存队列
     * 
     * 用作从内存创建的ByteBuffer的缓存。这些只是副本，因此只是内存本身的容器。
     * 
     * 这些在Pooled*ByteBuf的操作中经常需要，可能产生额外的GC，
     * 
     * 通过缓存副本可以大大减少GC。
     * 
     * 如果PoolChunk是非池化的，则此字段可能为null，因为池化ByteBuffer实例在这里没有意义。
     */
    private final Deque<ByteBuffer> cachedNioBuffers;

    // 运行时状态
    int freeBytes;                      // 当前可用字节数

    // 链表结构（用于在PoolChunkList中组织）
    PoolChunkList<T> parent;            // 父PoolChunkList
    
    PoolChunk<T> prev;                  // 前一个PoolChunk
    
    PoolChunk<T> next;                  // 下一个PoolChunk

    // TODO: 测试在竞争情况下添加填充是否有帮助
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 构造一个池化的内存块
     * 
     * @param arena 所属的内存池竞技场
     * @param base 内存基础对象
     * @param memory 实际内存对象
     * @param pageSize 页大小
     * @param pageShifts 页大小位移值
     * @param chunkSize 块总大小
     * @param maxPageIdx 最大页索引
     */
    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false;               // 标记为池化内存块
        
        this.arena = arena;
        
        this.base = base;
        
        this.memory = memory;
        
        this.pageSize = pageSize;
        
        this.pageShifts = pageShifts;
        
        this.chunkSize = chunkSize;
        
        freeBytes = chunkSize;          // 初始时所有字节都可用

        // 初始化可用运行块管理结构
        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        
        runsAvailMap = new LongLongHashMap(-1);
        
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        // 插入初始运行块，偏移 = 0，页数 = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        
        long initHandle = (long) pages << SIZE_SHIFT;
        
        insertAvailRun(0, pages, initHandle);

        // 初始化ByteBuffer缓存队列
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /**
     * 创建一个特殊的非池化内存块
     * 
     * @param arena 所属的内存池竞技场
     * @param base 内存基础对象
     * @param memory 实际内存对象
     * @param size 内存块大小
     */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;                // 标记为非池化内存块
        
        this.arena = arena;
        
        this.base = base;
        
        this.memory = memory;
        
        pageSize = 0;                   // 非池化块不需要页管理
        
        pageShifts = 0;
        
        runsAvailMap = null;            // 非池化块不需要运行块管理
        
        runsAvail = null;
        
        subpages = null;                // 非池化块不需要子页管理
        
        chunkSize = size;
        
        cachedNioBuffers = null;        // 非池化块不需要缓存
    }

    /**
     * 创建可用运行块队列数组
     * 
     * @param size 数组大小
     * @return 初始化好的队列数组
     */
    private static LongPriorityQueue[] newRunsAvailqueueArray(int size) {
        LongPriorityQueue[] queueArray = new LongPriorityQueue[size];
        
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new LongPriorityQueue();
        }
        
        return queueArray;
    }

    /**
     * 插入可用运行块到管理结构中
     * 
     * @param runOffset 运行块偏移
     * @param pages 页数
     * @param handle 句柄
     */
    private void insertAvailRun(int runOffset, int pages, long handle) {
        // 根据页数找到对应的队列索引
        int pageIdxFloor = arena.pages2pageIdxFloor(pages);
        
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        
        queue.offer(handle);

        // 插入运行块的第一页
        insertAvailRun0(runOffset, handle);
        
        if (pages > 1) {
            // 插入运行块的最后一页
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    /**
     * 将运行块信息插入到映射表中
     * 
     * @param runOffset 运行块偏移
     * @param handle 句柄
     */
    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        
        assert pre == -1;               // 确保之前没有映射
    }

    /**
     * 从管理结构中移除可用运行块
     * 
     * @param handle 要移除的运行块句柄
     */
    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        
        removeAvailRun(queue, handle);
    }

    /**
     * 从指定队列和映射表中移除可用运行块
     * 
     * @param queue 优先队列
     * @param handle 要移除的运行块句柄
     */
    private void removeAvailRun(LongPriorityQueue queue, long handle) {
        queue.remove(handle);

        int runOffset = runOffset(handle);
        
        int pages = runPages(handle);
        
        // 移除运行块的第一页
        runsAvailMap.remove(runOffset);
        
        if (pages > 1) {
            // 移除运行块的最后一页
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    /**
     * 计算运行块的最后一页偏移
     * 
     * @param runOffset 运行块起始偏移
     * @param pages 页数
     * @return 最后一页的偏移
     */
    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    /**
     * 根据偏移获取可用运行块
     * 
     * @param runOffset 运行块偏移
     * @return 运行块句柄，如果不存在则返回-1
     */
    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    /**
     * 获取内存块的使用率百分比
     * 
     * @return 使用率百分比（0-100）
     */
    @Override
    public int usage() {
        final int freeBytes;
        
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        
        return usage(freeBytes);
    }

    /**
     * 根据可用字节数计算使用率
     * 
     * @param freeBytes 可用字节数
     * @return 使用率百分比
     */
    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;                 // 完全使用
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        
        if (freePercentage == 0) {
            return 99;                  // 几乎完全使用，但不是100%
        }
        
        return 100 - freePercentage;
    }

    /**
     * 分配内存给ByteBuf
     * 
     * @param buf 要初始化的ByteBuf
     * @param reqCapacity 请求的容量
     * @param sizeIdx 大小索引
     * @param cache 线程缓存
     * @return 是否分配成功
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        final long handle;
        
        if (sizeIdx <= arena.smallMaxSizeIdx) {
            // 小内存分配 - 使用子页
            handle = allocateSubpage(sizeIdx);
            
            if (handle < 0) {
                return false;           // 分配失败
            }
            
            assert isSubpage(handle);
        } else {
            // 正常内存分配 - 使用运行块
            // runSize必须是pageSize的倍数
            int runSize = arena.sizeIdx2size(sizeIdx);
            
            handle = allocateRun(runSize);
            
            if (handle < 0) {
                return false;           // 分配失败
            }
        }

        // 从缓存中获取ByteBuffer，如果没有则为null
        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);
        
        return true;
    }

    /**
     * 分配运行块
     * 
     * @param runSize 请求的运行块大小
     * @return 分配的运行块句柄，失败返回-1
     */
    private long allocateRun(int runSize) {
        int pages = runSize >> pageShifts;          // 计算需要的页数
        
        int pageIdx = arena.pages2pageIdx(pages);   // 获取页索引

        synchronized (runsAvail) {
            // 找到第一个有足够大运行块的队列
            int queueIdx = runFirstBestFit(pageIdx);
            
            if (queueIdx == -1) {
                return -1;                          // 没有合适的运行块
            }

            // 获取此队列中偏移最小的运行块
            LongPriorityQueue queue = runsAvail[queueIdx];
            
            long handle = queue.poll();

            assert handle != LongPriorityQueue.NO_VALUE && !isUsed(handle) : "invalid handle: " + handle;

            // 从可用运行块管理结构中移除
            removeAvailRun(queue, handle);

            if (handle != -1) {
                handle = splitLargeRun(handle, pages);  // 分割大运行块
            }

            freeBytes -= runSize(pageShifts, handle);   // 更新可用字节数
            
            return handle;
        }
    }

    /**
     * 计算子页分配所需的运行块大小
     * 
     * @param sizeIdx 大小索引
     * @return 计算出的运行块大小
     */
    private int calculateRunSize(int sizeIdx) {
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        
        int runSize = 0;
        
        int nElements;

        final int elemSize = arena.sizeIdx2size(sizeIdx);

        // 找到pageSize和elemSize的最小公倍数
        do {
            runSize += pageSize;
            
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        // 确保元素数量不超过最大值
        while (nElements > maxElements) {
            runSize -= pageSize;
            
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        
        assert runSize <= chunkSize;
        
        assert runSize >= elemSize;

        return runSize;
    }

    /**
     * 找到第一个最佳适配的运行块队列
     * 
     * @param pageIdx 页索引
     * @return 队列索引，如果没有找到返回-1
     */
    private int runFirstBestFit(int pageIdx) {
        if (freeBytes == chunkSize) {
            return arena.nPSizes - 1;               // 如果完全空闲，返回最大队列
        }
        
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            LongPriorityQueue queue = runsAvail[i];
            
            if (queue != null && !queue.isEmpty()) {
                return i;                           // 找到第一个非空队列
            }
        }
        
        return -1;                                  // 没有找到合适的队列
    }

    /**
     * 分割大运行块
     * 
     * @param handle 要分割的运行块句柄
     * @param needPages 需要的页数
     * @return 分割后的运行块句柄
     */
    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;

        int totalPages = runPages(handle);
        
        assert needPages <= totalPages;

        int remPages = totalPages - needPages;      // 剩余页数

        if (remPages > 0) {
            int runOffset = runOffset(handle);

            // 跟踪尾部未使用的页面供以后使用
            int availOffset = runOffset + needPages;
            
            long availRun = toRunHandle(availOffset, remPages, 0);
            
            insertAvailRun(availOffset, remPages, availRun);

            // 返回已使用的部分
            return toRunHandle(runOffset, needPages, 1);
        }

        // 标记为已使用
        handle |= 1L << IS_USED_SHIFT;
        
        return handle;
    }

    /**
     * 创建/初始化指定标准化容量的新PoolSubpage
     * 
     * 在这里创建/初始化的任何PoolSubpage都会添加到拥有此PoolChunk的PoolArena的子页池中
     *
     * @param sizeIdx 标准化大小的大小索引
     * @return 内存映射中的索引
     */
    private long allocateSubpage(int sizeIdx) {
        // 获取PoolArena拥有的PoolSubPage池的头部并在其上同步
        // 这是必需的，因为我们可能将其添加回去，从而改变链表结构
        PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);
        
        synchronized (head) {
            // 分配新的运行块
            int runSize = calculateRunSize(sizeIdx);
            
            // runSize必须是pageSize的倍数
            long runHandle = allocateRun(runSize);
            
            if (runHandle < 0) {
                return -1;                          // 分配失败
            }

            int runOffset = runOffset(runHandle);
            
            assert subpages[runOffset] == null;
            
            int elemSize = arena.sizeIdx2size(sizeIdx);

            // 创建新的子页
            PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                               runSize(pageShifts, runHandle), elemSize);

            subpages[runOffset] = subpage;
            
            return subpage.allocate();
        }
    }

    /**
     * 释放子页或运行块
     * 
     * 当子页从PoolSubpage释放时，它可能被添加回拥有PoolArena的子页池。
     * 
     * 如果PoolArena中的子页池至少有一个给定elemSize的其他PoolSubpage，
     * 
     * 我们可以完全释放拥有的页面，使其可用于后续分配
     *
     * @param handle 要释放的句柄
     * @param normCapacity 标准化容量
     * @param nioBuffer NIO缓冲区
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        if (isSubpage(handle)) {
            // 处理子页释放
            int sizeIdx = arena.size2SizeIdx(normCapacity);
            
            PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

            int sIdx = runOffset(handle);
            
            PoolSubpage<T> subpage = subpages[sIdx];
            
            assert subpage != null && subpage.doNotDestroy;

            // 获取PoolArena拥有的PoolSubPage池的头部并在其上同步
            // 这是必需的，因为我们可能将其添加回去，从而改变链表结构
            synchronized (head) {
                if (subpage.free(head, bitmapIdx(handle))) {
                    // 子页仍在使用，不要释放它
                    return;
                }
                
                assert !subpage.doNotDestroy;
                
                // 清空数组中的槽位，因为它已被释放，我们不应再使用它
                subpages[sIdx] = null;
            }
        }

        // 开始释放运行块
        int pages = runPages(handle);

        synchronized (runsAvail) {
            // 合并连续的运行块，成功合并的运行块将从runsAvail和runsAvailMap中移除
            long finalRun = collapseRuns(handle);

            // 设置运行块为未使用
            finalRun &= ~(1L << IS_USED_SHIFT);
            
            // 如果是子页，将其设置为运行块
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);

            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            
            freeBytes += pages << pageShifts;       // 更新可用字节数
        }

        // 如果有NIO缓冲区且缓存未满，则缓存它
        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /**
     * 合并连续的运行块
     * 
     * @param handle 要合并的运行块句柄
     * @return 合并后的运行块句柄
     */
    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle));
    }

    /**
     * 向前合并连续的运行块
     * 
     * @param handle 当前运行块句柄
     * @return 合并后的运行块句柄
     */
    private long collapsePast(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            
            int runPages = runPages(handle);

            // 查找前一个运行块
            long pastRun = getAvailRunByOffset(runOffset - 1);
            
            if (pastRun == -1) {
                return handle;                      // 没有前一个运行块
            }

            int pastOffset = runOffset(pastRun);
            
            int pastPages = runPages(pastRun);

            // 检查是否连续
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                // 移除前一个运行块
                removeAvailRun(pastRun);
                
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;                      // 不连续，停止合并
            }
        }
    }

    /**
     * 向后合并连续的运行块
     * 
     * @param handle 当前运行块句柄
     * @return 合并后的运行块句柄
     */
    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            
            int runPages = runPages(handle);

            // 查找下一个运行块
            long nextRun = getAvailRunByOffset(runOffset + runPages);
            
            if (nextRun == -1) {
                return handle;                      // 没有下一个运行块
            }

            int nextOffset = runOffset(nextRun);
            
            int nextPages = runPages(nextRun);

            // 检查是否连续
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                // 移除下一个运行块
                removeAvailRun(nextRun);
                
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;                      // 不连续，停止合并
            }
        }
    }

    /**
     * 创建运行块句柄
     * 
     * @param runOffset 运行块偏移
     * @param runPages 运行块页数
     * @param inUsed 是否已使用（1表示已使用，0表示未使用）
     * @return 运行块句柄
     */
    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    /**
     * 初始化ByteBuf
     * 
     * @param buf 要初始化的ByteBuf
     * @param nioBuffer NIO缓冲区
     * @param handle 内存句柄
     * @param reqCapacity 请求容量
     * @param threadCache 线程缓存
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isRun(handle)) {
            // 初始化运行块ByteBuf
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                     reqCapacity, runSize(pageShifts, handle), arena.parent.threadCache());
        } else {
            // 初始化子页ByteBuf
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    /**
     * 使用子页初始化ByteBuf
     * 
     * @param buf 要初始化的ByteBuf
     * @param nioBuffer NIO缓冲区
     * @param handle 内存句柄
     * @param reqCapacity 请求容量
     * @param threadCache 线程缓存
     */
    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        
        assert s.doNotDestroy;
        
        assert reqCapacity <= s.elemSize;

        // 计算在内存块中的实际偏移
        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    /**
     * 获取块大小
     * 
     * @return 块大小
     */
    @Override
    public int chunkSize() {
        return chunkSize;
    }

    /**
     * 获取可用字节数
     * 
     * @return 可用字节数
     */
    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    /**
     * 返回块的字符串表示
     * 
     * @return 包含使用率和大小信息的字符串
     */
    @Override
    public String toString() {
        final int freeBytes;
        
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    /**
     * 销毁此块
     */
    void destroy() {
        arena.destroyChunk(this);
    }

    /**
     * 从句柄中提取运行块偏移
     * 
     * @param handle 句柄
     * @return 运行块偏移
     */
    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    /**
     * 从句柄中计算运行块大小
     * 
     * @param pageShifts 页大小位移
     * @param handle 句柄
     * @return 运行块大小（字节）
     */
    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    /**
     * 从句柄中提取运行块页数
     * 
     * @param handle 句柄
     * @return 运行块页数
     */
    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    /**
     * 检查句柄是否表示已使用的内存
     * 
     * @param handle 句柄
     * @return 如果已使用返回true
     */
    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    /**
     * 检查句柄是否表示运行块
     * 
     * @param handle 句柄
     * @return 如果是运行块返回true
     */
    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    /**
     * 检查句柄是否表示子页
     * 
     * @param handle 句柄
     * @return 如果是子页返回true
     */
    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    /**
     * 从句柄中提取位图索引
     * 
     * @param handle 句柄
     * @return 位图索引
     */
    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
