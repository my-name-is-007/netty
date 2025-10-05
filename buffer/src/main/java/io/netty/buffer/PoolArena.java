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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.PoolChunk.isSubpage;
import static java.lang.Math.max;

/**
 * 内存池竞技场（Arena）- Netty内存池管理的核心组件
 * 
 * 这个类是Netty内存池系统的核心，负责管理和分配不同大小的内存块。
 * 它采用了类似于jemalloc的内存分配策略，将内存分为不同的大小类别进行管理。
 * 
 * 主要功能：
 * 1. 管理小内存块（Small）和普通内存块（Normal）的分配
 * 2. 维护多个PoolChunkList来管理不同使用率的内存块
 * 3. 提供线程缓存支持以提高分配性能
 * 4. 统计内存分配和释放的各种指标
 * 
 * 内存分类详解：
 * - Small: 小于pageSize的内存分配，使用subpage机制
 *   例如：分配512字节的ByteBuf，会从对应的subpage池中分配
 *   典型大小：16B, 32B, 48B, 64B, 80B, 96B, 112B, 128B, 144B, 160B, 176B, 192B, 208B, 224B, 240B, 256B,
 *           272B, 288B, 304B, 320B, 336B, 352B, 368B, 384B, 400B, 416B, 432B, 448B, 464B, 480B, 496B, 512B,
 *           528B, 544B, 560B, 576B, 592B, 608B, 624B, 640B, 656B, 672B, 688B, 704B, 720B, 736B, 752B, 768B,
 *           784B, 800B, 816B, 832B, 848B, 864B, 880B, 896B, 912B, 928B, 944B, 960B, 976B, 992B, 1008B, 1024B,
 *           1040B, 1056B, 1072B, 1088B, 1104B, 1120B, 1136B, 1152B, 1168B, 1184B, 1200B, 1216B, 1232B, 1248B,
 *           1264B, 1280B, 1296B, 1312B, 1328B, 1344B, 1360B, 1376B, 1392B, 1408B, 1424B, 1440B, 1456B, 1472B,
 *           1488B, 1504B, 1520B, 1536B, 1552B, 1568B, 1584B, 1600B, 1616B, 1632B, 1648B, 1664B, 1680B, 1696B,
 *           1712B, 1728B, 1744B, 1760B, 1776B, 1792B, 1808B, 1824B, 1840B, 1856B, 1872B, 1888B, 1904B, 1920B,
 *           1936B, 1952B, 1968B, 1984B, 2000B, 2016B, 2032B, 2048B, 2064B, 2080B, 2096B, 2112B, 2128B, 2144B,
 *           2160B, 2176B, 2192B, 2208B, 2224B, 2240B, 2256B, 2272B, 2288B, 2304B, 2320B, 2336B, 2352B, 2368B,
 *           2384B, 2400B, 2416B, 2432B, 2448B, 2464B, 2480B, 2496B, 2512B, 2528B, 2544B, 2560B, 2576B, 2592B,
 *           2608B, 2624B, 2640B, 2656B, 2672B, 2688B, 2704B, 2720B, 2736B, 2752B, 2768B, 2784B, 2800B, 2816B,
 *           2832B, 2848B, 2864B, 2880B, 2896B, 2912B, 2928B, 2944B, 2960B, 2976B, 2992B, 3008B, 3024B, 3040B,
 *           3056B, 3072B, 3088B, 3104B, 3120B, 3136B, 3152B, 3168B, 3184B, 3200B, 3216B, 3232B, 3248B, 3264B,
 *           3280B, 3296B, 3312B, 3328B, 3344B, 3360B, 3376B, 3392B, 3408B, 3424B, 3440B, 3456B, 3472B, 3488B,
 *           3504B, 3520B, 3536B, 3552B, 3568B, 3584B, 3600B, 3616B, 3632B, 3648B, 3664B, 3680B, 3696B, 3712B,
 *           3728B, 3744B, 3760B, 3776B, 3792B, 3808B, 3824B, 3840B, 3856B, 3872B, 3888B, 3904B, 3920B, 3936B,
 *           3952B, 3968B, 3984B, 4000B, 4016B, 4032B, 4048B, 4064B, 4080B, 4096B
 * 
 * - Normal: pageSize到chunkSize之间的内存分配
 *   例如：分配8KB的ByteBuf，会从PoolChunk中分配一个完整的page
 *   典型大小：8KB, 16KB, 32KB, 64KB, 128KB, 256KB, 512KB, 1MB, 2MB, 4MB, 8MB
 * 
 * - Huge: 超过chunkSize的大内存分配，不使用池化
 *   例如：分配20MB的ByteBuf，会直接创建独立的内存块，不进入池中管理
 *   这类内存在使用完毕后直接释放，不会被缓存
 * 
 * PoolChunkList使用率分级详解：
 * - qInit (0%-25%): 新创建的chunk，使用率很低，优先用于分配以提高利用率
 * - q000 (1%-50%): 使用率较低的chunk，仍有较多可用空间
 * - q025 (25%-75%): 使用率中等的chunk，平衡了可用性和碎片化
 * - q050 (50%-100%): 使用率较高的chunk，可用空间有限但仍可分配
 * - q075 (75%-100%): 使用率很高的chunk，接近满载状态
 * - q100 (100%): 完全使用的chunk，无可用空间，等待释放后移动到其他列表
 * 
 * 运行时表现：
 * 1. 分配优先级：优先从使用率50%的chunk开始分配，这样可以：
 *    - 避免创建过多新chunk（减少内存开销）
 *    - 避免使用率过低的chunk长期占用内存
 *    - 保持内存使用的均衡性
 * 
 * 2. 线程缓存机制：每个线程都有独立的缓存，减少锁竞争：
 *    - Small内存：缓存最近释放的小内存块，下次分配时直接复用
 *    - Normal内存：缓存最近释放的页面，避免重复的chunk查找
 * 
 * 3. 内存对齐：DirectArena支持内存对齐，提高CPU缓存效率：
 *    - 默认对齐大小：0（不对齐）
 *    - 常用对齐大小：64字节（CPU缓存行）
 * 
 * 性能优化策略：
 * 1. 减少锁竞争：使用线程本地缓存，只有缓存未命中时才需要获取Arena锁
 * 2. 内存局部性：相同大小的内存分配集中管理，提高缓存局部性
 * 3. 碎片化控制：通过使用率分级管理，避免内存碎片化
 * 4. 批量操作：一次分配一个完整的chunk，然后逐步分割使用
 * 
 * 典型使用场景：
 * 1. HTTP服务器：大量小的HTTP请求/响应缓冲区（1KB-8KB）
 * 2. 数据库连接池：中等大小的查询结果缓冲区（8KB-1MB）
 * 3. 文件传输：大的文件块缓冲区（1MB-16MB）
 * 4. 实时通信：小的消息包缓冲区（64B-4KB）
 * 
 * @param <T> 内存类型，可以是byte[]（堆内存）或ByteBuffer（直接内存）
 */
abstract class PoolArena<T> extends SizeClasses implements PoolArenaMetric {
    
    // 检查平台是否支持Unsafe操作，用于优化内存操作
    // 在支持Unsafe的平台上，可以使用更高效的内存复制和操作方法
    // 运行时表现：如果为true，会使用PooledUnsafeHeapByteBuf和PooledUnsafeDirectByteBuf
    //           如果为false，会使用PooledHeapByteBuf和PooledDirectByteBuf
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    /**
     * 内存大小分类枚举
     * 
     * 这个枚举定义了内存分配的两个主要类别，用于在运行时快速判断使用哪种分配策略：
     * 
     * Small: 小内存块，通常小于一个页面大小（默认8KB）
     * - 使用subpage机制进行管理
     * - 多个小块共享一个page，提高内存利用率
     * - 分配速度快，但可能产生内部碎片
     * - 典型应用：HTTP头部、小消息包、协议解析缓冲区
     * 
     * Normal: 普通内存块，介于页面大小和块大小之间（8KB-16MB）
     * - 直接从PoolChunk中分配完整的page或多个page
     * - 使用二叉树算法管理可用页面
     * - 分配效率高，碎片化程度低
     * - 典型应用：文件读写缓冲区、网络传输缓冲区、图像处理缓冲区
     * 
     * 运行时判断逻辑：
     * if (sizeIdx <= smallMaxSizeIdx) -> Small
     * else if (sizeIdx < nSizes) -> Normal  
     * else -> Huge (不在此枚举中，直接分配)
     */
    enum SizeClass {
        Small,
        Normal
    }

    // 父级内存分配器，用于获取全局配置和管理
    // 运行时作用：
    // 1. 获取线程缓存：parent.threadCache()
    // 2. 获取全局配置：页面大小、块大小、缓存对齐等
    // 3. 统计信息汇总：所有Arena的统计信息最终汇总到parent
    final PooledByteBufAllocator parent;

    // 小内存子页面池的数量
    // 固定值：通常为512，对应512种不同的小内存规格
    // 运行时表现：每个规格对应一个独立的subpage链表，避免不同大小的内存混合管理
    // 例如：smallSubpagePools[0]管理16字节的subpage，smallSubpagePools[1]管理32字节的subpage
    final int numSmallSubpagePools;
    
    // 直接内存缓存对齐大小，用于优化CPU缓存性能
    // 常见值：
    // - 0: 不进行内存对齐（默认值）
    // - 64: 按64字节对齐（CPU缓存行大小）
    // - 128: 按128字节对齐（某些CPU的缓存行大小）
    // 运行时表现：
    // - 对齐后的内存访问速度更快，减少缓存未命中
    // - 会浪费一些内存空间用于对齐填充
    // - 只对DirectArena有效，HeapArena不需要对齐
    final int directMemoryCacheAlignment;
    
    // 小内存子页面池数组，每个元素对应一个特定大小的内存池
    // 数组结构：smallSubpagePools[sizeIdx] -> PoolSubpage链表头节点
    // 运行时表现：
    // - 每个数组元素是一个双向循环链表的头节点
    // - 链表中的每个节点代表一个可用的subpage
    // - 分配时从链表头开始查找可用的subpage
    // - 释放时将subpage重新加入对应的链表
    // 例如：分配256字节时，会查找smallSubpagePools[size2SizeIdx(256)]对应的链表
    private final PoolSubpage<T>[] smallSubpagePools;

    // PoolChunkList链表，按照内存使用率分为不同等级
    // 这种分级管理的优势：
    // 1. 快速定位：根据使用率快速找到合适的chunk
    // 2. 负载均衡：避免某些chunk过度使用而其他chunk闲置
    // 3. 碎片控制：使用率相近的chunk放在一起，减少碎片化
    // 4. 性能优化：优先使用中等使用率的chunk，平衡分配速度和内存利用率
    
    // q050: 使用率50%-100%的内存块
    // 运行时表现：这是分配的首选列表，使用率适中，既有可用空间又不会太浪费
    private final PoolChunkList<T> q050;
    
    // q025: 使用率25%-75%的内存块  
    // 运行时表现：第二优先级，当q050无法分配时使用
    private final PoolChunkList<T> q025;
    
    // q000: 使用率1%-50%的内存块
    // 运行时表现：第三优先级，使用率较低，但仍有一定使用量
    private final PoolChunkList<T> q000;
    
    // qInit: 使用率0%-25%的内存块（初始状态）
    // 运行时表现：第四优先级，新chunk首先进入此列表
    private final PoolChunkList<T> qInit;
    
    // q075: 使用率75%-100%的内存块
    // 运行时表现：第五优先级，使用率很高，可用空间有限
    private final PoolChunkList<T> q075;
    
    // q100: 使用率100%的内存块（已满）
    // 运行时表现：完全使用的chunk，不参与分配，等待释放后移动到其他列表
    private final PoolChunkList<T> q100;

    // 所有PoolChunkList的只读列表，用于监控和统计
    // 运行时作用：
    // 1. JMX监控：通过此列表暴露内存使用情况
    // 2. 调试信息：toString()方法使用此列表生成详细信息
    // 3. 内存分析：可以分析每个使用率级别的chunk数量和内存占用
    private final List<PoolChunkListMetric> chunkListMetrics;

    // ===== 内存分配统计指标 =====
    // 这些统计指标用于监控内存池的使用情况，帮助调优和问题诊断
    
    // 普通内存分配次数（需要同步保护）
    // 运行时表现：每次分配Normal大小的内存时递增
    // 同步策略：使用synchronized(this)保护，因为分配频率相对较低
    private long allocationsNormal;
    
    // 小内存分配次数（使用LongCounter保证线程安全）
    // 运行时表现：每次分配Small大小的内存时递增
    // 同步策略：使用LongCounter避免锁竞争，因为小内存分配非常频繁
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    
    // 大内存分配次数（使用LongCounter保证线程安全）
    // 运行时表现：每次分配Huge大小的内存时递增
    // 同步策略：使用LongCounter，虽然大内存分配不频繁，但避免与其他操作产生锁竞争
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    
    // 当前活跃的大内存字节数（使用LongCounter保证线程安全）
    // 运行时表现：
    // - 分配大内存时增加对应字节数
    // - 释放大内存时减少对应字节数
    // - 可以实时监控大内存的使用情况，防止内存泄漏
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    // 小内存释放次数
    // 运行时表现：每次释放Small大小的内存时递增
    // 同步策略：在synchronized(this)块中更新
    private long deallocationsSmall;
    
    // 普通内存释放次数
    // 运行时表现：每次释放Normal大小的内存时递增
    // 同步策略：在synchronized(this)块中更新
    private long deallocationsNormal;

    // 大内存释放次数（使用LongCounter保证线程安全）
    // 运行时表现：每次释放Huge大小的内存时递增
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // 使用此Arena的线程缓存数量
    // 运行时表现：
    // - 每个线程首次使用Arena时递增
    // - 线程缓存被清理时递减
    // - 可以监控有多少线程在使用此Arena
    // - 帮助判断Arena的负载分布是否均衡
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: 测试在高并发情况下添加填充是否有助于性能
    // 这些填充字段用于避免伪共享（False Sharing）问题
    // 伪共享：多个线程访问同一缓存行中的不同变量，导致缓存行频繁失效
    // 解决方案：在关键字段前后添加填充，确保它们独占缓存行
    // 运行时影响：可能提高多线程环境下的性能，但会增加内存开销
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 构造函数 - 初始化内存池竞技场
     * 
     * 这个构造函数是Arena初始化的核心，负责建立整个内存管理体系的基础架构。
     * 
     * 初始化过程详解：
     * 1. 继承SizeClasses：获得大小分类的能力，包括size2SizeIdx、sizeIdx2size等方法
     * 2. 创建subpage池：为每种小内存大小创建独立的管理链表
     * 3. 构建ChunkList链：建立按使用率分级的chunk管理体系
     * 4. 设置双向链表：让chunk可以在不同使用率级别间流动
     * 
     * 参数说明：
     * @param parent 父级内存分配器
     *               运行时作用：提供全局配置、线程缓存访问、统计信息汇总
     *               典型值：PooledByteBufAllocator实例
     * 
     * @param pageSize 页面大小，通常是8KB
     *                 固定值：8192字节（8KB）
     *                 运行时作用：
     *                 - 决定Normal内存分配的最小单位
     *                 - 影响内存对齐和碎片化程度
     *                 - 与操作系统页面大小匹配，提高性能
     * 
     * @param pageShifts 页面大小的位移值，用于快速计算
     *                   固定值：13（因为8KB = 2^13）
     *                   运行时作用：
     *                   - 快速计算页面数量：size >> pageShifts
     *                   - 快速计算页面偏移：size & ((1 << pageShifts) - 1)
     *                   - 避免除法运算，提高性能
     * 
     * @param chunkSize 内存块大小，通常是16MB
     *                  固定值：16777216字节（16MB）
     *                  运行时作用：
     *                  - 决定每个PoolChunk管理的内存大小
     *                  - 影响内存分配的粒度和效率
     *                  - 超过此大小的分配被视为Huge，不进入池管理
     * 
     * @param cacheAlignment 缓存对齐大小，用于优化性能
     *                       常见值：0（不对齐）、64（CPU缓存行）、128（某些CPU）
     *                       运行时作用：
     *                       - 提高CPU缓存命中率
     *                       - 减少内存访问延迟
     *                       - 只对直接内存有效
     * 
     * 初始化后的内存布局：
     * 
     * Arena
     * ├── smallSubpagePools[512] (每个对应一种小内存大小)
     * │   ├── [0] -> 16字节的subpage链表
     * │   ├── [1] -> 32字节的subpage链表
     * │   ├── ...
     * │   └── [511] -> 4096字节的subpage链表
     * │
     * └── ChunkList链（按使用率分级）
     *     ├── qInit (0%-25%) -> q000 (1%-50%) -> q025 (25%-75%) -> q050 (50%-100%) -> q075 (75%-100%) -> q100 (100%)
     *     └── 每个ChunkList包含多个PoolChunk，每个PoolChunk管理16MB内存
     * 
     * 性能优化设计：
     * 1. 预分配结构：所有管理结构在初始化时创建，运行时无需动态分配
     * 2. 双向链表：chunk可以根据使用率在不同列表间快速移动
     * 3. 分级管理：根据使用率优化分配策略，平衡性能和内存利用率
     * 4. 缓存友好：相同大小的内存分配集中管理，提高缓存局部性
     */
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
            int pageShifts, int chunkSize, int cacheAlignment) {
        super(pageSize, pageShifts, chunkSize, cacheAlignment);
        this.parent = parent;
        directMemoryCacheAlignment = cacheAlignment;

        // 初始化小内存子页面池
        // numSmallSubpagePools通常为512，对应512种不同的小内存大小
        // 从16字节到4096字节，按特定算法递增
        numSmallSubpagePools = nSubpages;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        
        // 为每个大小创建一个子页面池头节点
        // 头节点是双向循环链表的哨兵，简化链表操作
        // 运行时表现：head.next指向第一个可用subpage，head.prev指向最后一个
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead();
        }

        // 初始化PoolChunkList链表，按使用率分级管理
        // 使用率越高的放在后面，便于快速找到可用空间
        // 
        // 分配优先级设计原理：
        // 1. 从q050开始：使用率50%的chunk既有足够空间，又不会太浪费
        // 2. 然后q025：使用率25%的chunk，空间充足
        // 3. 再q000：使用率很低的chunk，避免长期占用
        // 4. 最后qInit：新chunk，只有在必要时才使用
        // 5. q075作为备选：使用率75%的chunk，空间有限但仍可用
        // 6. q100不参与分配：完全使用的chunk，等待释放
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        // 设置双向链表的前驱指针
        // 这样chunk可以根据使用率变化在列表间双向移动
        // 例如：当chunk使用率从30%降到20%时，会从q025移动到q000
        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);  // q000是最低级别，没有更低的列表
        qInit.prevList(qInit); // qInit指向自己，新chunk在此列表中循环

        // 创建监控指标列表
        // 按使用率从低到高排序，便于监控和调试
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /**
     * 创建一个新的子页面池头节点
     * 
     * 头节点是一个双向循环链表的哨兵节点，简化链表操作
     * 
     * @return 新创建的子页面池头节点
     */
    private PoolSubpage<T> newSubpagePoolHead() {
        PoolSubpage<T> head = new PoolSubpage<T>();
        head.prev = head;
        head.next = head;
        return head;
    }

    /**
     * 创建指定大小的子页面池数组
     * 
     * @param size 数组大小
     * @return 新创建的子页面池数组
     */
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    /**
     * 判断当前Arena是否管理直接内存
     * 
     * @return true表示直接内存，false表示堆内存
     */
    abstract boolean isDirect();

    /**
     * 分配一个PooledByteBuf
     * 
     * 这是外部调用的主要入口，会创建一个新的ByteBuf并分配内存。
     * 这个方法是内存分配的门面（Facade），隐藏了内部复杂的分配逻辑。
     * 
     * 执行流程：
     * 1. 创建新的ByteBuf实例（根据内存类型选择具体实现）
     * 2. 调用内部allocate方法为ByteBuf分配实际内存
     * 3. 返回已分配内存的ByteBuf
     * 
     * 运行时表现：
     * - 对于堆内存：创建PooledHeapByteBuf或PooledUnsafeHeapByteBuf
     * - 对于直接内存：创建PooledDirectByteBuf或PooledUnsafeDirectBuf     * - Unsafe版本性能更好，但需要平台支持
     * 
     * 性能考虑：
     * - ByteBuf对象本身的创建开销很小（主要是字段初始化）
     * - 真正的性能开销在内存分配上
     * - 通过对象池可以进一步优化ByteBuf对象的创建
     * 
     * 分配策略：
     * 1. Small内存分配（< pageSize，通常< 8KB）：
     *    - 使用subpage机制，多个小块共享一个page
     *    - 优点：内存利用率高，分配速度快
     *    - 缺点：可能产生内部碎片
     *    - 适用场景：HTTP头部、小消息包、协议解析缓冲区
     *    - 实际案例：分配512字节用于HTTP请求头解析
     * 
     * 2. Normal内存分配（pageSize ~ chunkSize，8KB ~ 16MB）：
     *    - 直接从PoolChunk分配完整页面
     *    - 优点：碎片化程度低，管理简单
     *    - 缺点：可能浪费空间（如果请求大小不是页面的整数倍）
     *    - 适用场景：文件读写、网络传输、图像处理
     *    - 实际案例：分配64KB用于文件上传缓冲区
     * 
     * 3. Huge内存分配（> chunkSize，> 16MB）：
     *    - 不使用池化，直接分配独立内存块
     *    - 优点：避免大内存占用池资源，影响小内存分配
     *    - 缺点：无法复用，每次都需要系统调用
     *    - 适用场景：大文件传输、视频处理、批量数据处理
     *    - 实际案例：分配100MB用于视频文件传输
     * 
     * 大小判断逻辑：
     * - sizeIdx = size2SizeIdx(reqCapacity)：将请求大小转换为大小索引
     * - smallMaxSizeIdx：小内存的最大索引，通常对应4KB
     * - nSizes：所有大小类别的数量，超过此值为Huge
     * 
     * 内存对齐处理：
     * - 对于直接内存，如果设置了cacheAlignment，会进行内存对齐
     * - 对齐可以提高CPU缓存性能，但会增加内存开销
     * - 堆内存不需要对齐，因为JVM已经处理了对齐问题
     * 
     * 性能优化：
     * 1. 快速路径：先尝试线程缓存，避免锁竞争
     * 2. 大小预计算：使用查表法快速确定分配策略
     * 3. 分支预测：将最常用的Small分配放在第一个分支
     * 4. 内存预分配：chunk和subpage都是预分配的，减少运行时开销
     * 
     * 使用场景：
     * - HTTP服务器：大量小的HTTP请求/响应缓冲区（1KB-8KB）
     * - 数据库连接池：中等大小的查询结果缓冲区（8KB-1MB）
     * - 文件传输：大的文件块缓冲区（1MB-16MB）
     * - 实时通信：小的消息包缓冲区（64B-4KB）
     * 
     * 错误处理：
     * - 如果内存不足，会抛出OutOfMemoryError
     * - 如果参数无效，会抛出IllegalArgumentException
     * - 分配失败时，ByteBuf保持未初始化状态
     *     * @param cache 线程本地缓存，用于提高分配性能
     *              运行时作用：
     *              - 首先尝试从缓存中分配，避免锁竞争
     *              - 缓存未命中时才进入Arena的分配逻辑
     *              - 可以为null，表示不使用缓存（性能较低）
     * 
     * @param reqCapacity 请求的容量大小
     *                    范围：1 到 Integer.MAX_VALUE
     *                    运行时处理：
     *                    - 会被标准化为最接近的大小类别
     *                    - 例如：请求100字节，可能分配112字节
     * 
     * @param maxCapacity 最大容量限制
     *                    作用：限制ByteBuf可以扩容的最大大小
     *                    典型值：Integer.MAX_VALUE（无限制）
     * 
     * @return 分配好的PooledByteBuf
     *         特点：
     *         - 已分配内存，可以立即使用
     *         - 读写索引都为0
     *         - 容量为reqCapacity（或标准化后的大小）
     * 
     * 使用示例：
     * ```java
     * // 分配1KB的直接内存ByteBuf
     * PooledByteBuf<ByteBuffer> buf = directArena.allocate(cache, 1024, Integer.MAX_VALUE);
     * 
     * // 分配512字节的堆内存ByteBuf
     * PooledByteBuf<byte[]> buf = heapArena.allocate(cache, 512, 4096);
     * ```
     */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 为已存在的ByteBuf分配内存
     * 
     * 根据请求大小选择不同的分配策略：
     * - Small: 使用子页面分配
     * - Normal: 使用普通页面分配  
     * - Huge: 直接分配大内存块
     * 
     * @param cache 线程本地缓存
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int sizeIdx = size2SizeIdx(reqCapacity);

        if (sizeIdx <= smallMaxSizeIdx) {
            // 小内存分配，使用子页面机制
            tcacheAllocateSmall(cache, buf, reqCapacity, sizeIdx);
        } else if (sizeIdx < nSizes) {
            // 普通内存分配，使用页面机制
            tcacheAllocateNormal(cache, buf, reqCapacity, sizeIdx);
        } else {
            // 大内存分配，不使用池化，直接分配
            int normCapacity = directMemoryCacheAlignment > 0
                    ? normalizeSize(reqCapacity) : reqCapacity;
            allocateHuge(buf, normCapacity);
        }
    }

    /**
     * 分配小内存块（通过线程缓存）
     * 
     * 这是小内存分配的核心方法，采用两级分配策略：
     * 1. 优先从线程缓存分配（快速路径）
     * 2. 缓存未命中时从subpage池分配（慢速路径）
     * 
     * 小内存分配的特点：
     * - 频率极高：在典型的Web应用中，小内存分配占总分配次数的70%-90%
     * - 大小固定：每种大小都有预定义的规格，避免碎片化
     * - 共享页面：多个小块共享一个8KB的页面，提高内存利用率
     * 
     * 线程缓存机制详解：
     * - 每个线程维护最近释放的小内存块
     * - 缓存大小：每种大小通常缓存32-512个块
     * - 命中率：通常在85%-95%之间
     * - 优势：无锁分配，性能提升10-50倍
     * 
     * Subpage池机制详解：
     * - 每种大小维护一个双向链表
     * - 链表头：哨兵节点，简化插入删除操作
     * - 同步策略：在头节点上同步，粒度细，减少锁竞争
     * - 分配策略：从链表头开始查找，FIFO顺序
     * 
     * 运行时性能表现：
     * 1. 缓存命中（85%-95%的情况）：
     *    - 时间复杂度：O(1)
     *    - 无锁操作，延迟极低（~10ns）
     *    - 无内存分配，直接复用
     * 
     * 2. 缓存未命中，subpage可用（5%-10%的情况）：
     *    - 时间复杂度：O(1)
     *    - 需要获取锁，延迟中等（~100ns）
     *    - 从现有subpage分配
     * 
     * 3. 缓存未命中，需要新subpage（1%-5%的情况）：
     *    - 时间复杂度：O(log n)
     *    - 需要分配新页面，延迟较高（~1μs）
     *    - 触发chunk分配逻辑
     * 
     * 内存布局示例（以256字节为例）：
     * ```
     * Page (8KB)
     * ├── Subpage Header (元数据)
     * ├── Block 0 (256B) ← 可分配
     * ├── Block 1 (256B) ← 已分配
     * ├── Block 2 (256B) ← 可分配
     * ├── ...
     * └── Block 31 (256B) ← 总共32个块
     * ```
     * 
     * 同步策略分析：
     * - 为什么在头节点同步？
     *   1. 粒度细：只锁定特定大小的subpage链表
     *   2. 冲突少：不同大小的分配不会互相阻塞
     *   3. 时间短：只保护链表操作，不包含内存分配
     * 
     * - 双重检查模式：
     *   1. 先检查是否需要新分配（needsNormalAllocation）
     *   2. 在锁外执行耗时的普通分配
     *   3. 避免在锁内执行复杂操作
     * 
     * @param cache 线程本地缓存
     *              缓存结构：MemoryRegionCache[] smallSubPageCaches
     *              每个数组元素对应一种大小的缓存队列
     *              队列长度：通常为256-512个条目
     * 
     * @param buf 要分配内存的ByteBuf
     *            分配前状态：memory=null, chunk=null, handle=0
     *            分配后状态：所有字段都被正确设置
     * 
     * @param reqCapacity 请求的容量大小
     *                    实际分配：可能大于请求大小（向上取整到标准大小）
     *                    例如：请求100字节，实际分配112字节
     * 
     * @param sizeIdx 大小索引
     *                范围：0 到 smallMaxSizeIdx（通常是0-255）
     *                用途：快速定位对应的subpage池和缓存队列
     * 
     * 典型使用场景：
     * 1. HTTP请求解析：分配1-4KB缓冲区解析请求头
     * 2. JSON序列化：分配512B-2KB缓冲区构建JSON字符串
     * 3. 协议编码：分配64B-256B缓冲区编码协议包头
     * 4. 临时计算：分配小缓冲区进行数据转换和计算
     * 
     * 性能调优建议：
     * 1. 增加缓存大小：提高命中率，但会增加内存开销
     * 2. 预热缓存：应用启动时预分配一些常用大小的内存
     * 3. 监控命中率：通过JMX监控缓存效果，调整配置
     * 4. 避免大小抖动：尽量使用固定大小，避免频繁的大小变化
     */
    private void tcacheAllocateSmall(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                     final int sizeIdx) {

        if (cache.allocateSmall(this, buf, reqCapacity, sizeIdx)) {
            // 成功从缓存中分配，直接返回
            // 这是最快的路径，占85%-95%的分配
            // 性能特点：无锁、O(1)时间复杂度、延迟~10ns
            return;
        }

        /*
         * 缓存未命中，需要从subpage池分配
         * 
         * 同步设计说明：
         * 在头节点上同步，这是必需的，因为PoolChunk#allocateSubpage(int)和
         * PoolChunk#free(long)可能会修改双向链表。
         * 
         * 为什么选择头节点同步？
         * 1. 粒度控制：只锁定特定大小的subpage链表，不影响其他大小
         * 2. 减少竞争：不同大小的分配可以并行进行
         * 3. 简化逻辑：头节点是固定的，避免锁对象变化
         * 4. 性能优化：锁的持有时间很短，只保护链表操作
         */
        final PoolSubpage<T> head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        synchronized (head) {
            final PoolSubpage<T> s = head.next;
            needsNormalAllocation = s == head;  // 链表为空，只有头节点
            if (!needsNormalAllocation) {
                // 找到可用的子页面，直接分配
                // 验证subpage的有效性和大小匹配
                assert s.doNotDestroy && s.elemSize == sizeIdx2size(sizeIdx);
                long handle = s.allocate();
                assert handle >= 0;  // 分配成功的handle总是非负数
                // 初始化ByteBuf，关联到subpage
                s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
            }
        }

        if (needsNormalAllocation) {
            // 没有可用的子页面，需要分配新的普通页面
            // 这种情况相对少见（1%-5%），但开销较大
            // 需要从chunk中分配新的page，然后创建subpage
            synchronized (this) {
                allocateNormal(buf, reqCapacity, sizeIdx, cache);
            }
        }

        // 增加小内存分配计数
        // 使用LongCounter保证线程安全，避免锁竞争
        incSmallAllocation();
    }

    /**
     * 分配普通内存块（通过线程缓存）
     * 
     * 普通内存分配处理pageSize到chunkSize之间的内存请求（通常8KB-16MB）。
     * 与小内存分配类似，也采用两级策略：缓存优先，然后从chunk分配。
     * 
     * 普通内存的特点：
     * - 分配频率：中等，占总分配次数的5%-25%
     * - 大小范围：8KB到16MB，跨度很大
     * - 页面对齐：总是分配完整的页面（8KB的整数倍）
     * - 管理简单：不需要subpage的复杂管理逻辑
     * 
     * 线程缓存策略：
     * - 缓存结构：按大小分类的队列数组
     * - 缓存容量：每种大小通常缓存8-64个条目
     * - 命中率：通常在60%-85%之间（低于小内存）
     * - 原因：普通内存大小变化更大，缓存效果相对较差
     * 
     * 分配性能分析：
     * 1. 缓存命中（60%-85%）：
     *    - 时间复杂度：O(1)
     *    - 延迟：~50ns（比小内存稍慢，因为内存块更大）
     *    - 无锁操作
     * 
     * 2. 缓存未命中（15%-40%）：
     *    - 时间复杂度：O(log n)
     *    - 延迟：~500ns-2μs
     *    - 需要在chunk中查找连续的页面
     * 
     * 内存布局示例（64KB分配）：
     * ```
     * Chunk (16MB)
     * ├── Page 0 (8KB) ← 已分配给其他用途
     * ├── Page 1 (8KB) ← 分配给此请求
     * ├── Page 2 (8KB) ← 分配给此请求
     * ├── ...
     * ├── Page 8 (8KB) ← 分配给此请求（共8个页面=64KB）
     * ├── Page 9 (8KB) ← 可用
     * └── ...
     * ```
     * 
     * 同步策略：
     * - Arena级别同步：synchronized(this)
     * - 原因：普通内存分配涉及chunk的全局状态变化
     * - 影响：所有普通内存分配都会串行化
     * - 优化：通过线程缓存减少锁竞争
     * 
     * @param cache 线程本地缓存
     *              结构：MemoryRegionCache[] normalCaches
     *              特点：缓存容量较小，因为普通内存块较大
     * 
     * @param buf 要分配内存的ByteBuf
     *            要求：必须是新创建的，未初始化的ByteBuf
     * 
     * @param reqCapacity 请求的容量大小
     *                    对齐：会向上对齐到页面边界
     *                    例如：请求10KB，实际分配16KB（2个页面）
     * 
     * @param sizeIdx 大小索引
     *                范围：smallMaxSizeIdx+1 到 nSizes-1
     *                用途：确定需要分配的页面数量
     * 
     * 典型使用场景：
     * 1. 文件I/O：分配64KB-1MB缓冲区进行文件读写
     * 2. 网络传输：分配32KB-512KB缓冲区进行批量数据传输
     * 3. 图像处理：分配1MB-8MB缓冲区存储图像数据
     * 4. 数据库操作：分配128KB-2MB缓冲区进行查询结果缓存
     * 
     * 性能优化建议：
     * 1. 合理设置缓存大小：平衡内存开销和命中率
     * 2. 预分配常用大小：为常用的文件块大小预分配内存
     * 3. 批量操作：尽量批量分配和释放，提高缓存效率
     * 4. 监控碎片化：定期检查chunk的使用率分布
     */
    private void tcacheAllocateNormal(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                      final int sizeIdx) {
        if (cache.allocateNormal(this, buf, reqCapacity, sizeIdx)) {
            // 成功从缓存中分配，直接返回
            // 缓存命中率通常为60%-85%
            // 性能：无锁操作，延迟~50ns
            return;
        }
        
        // 缓存未命中，需要从Arena分配
        // 这里需要Arena级别的同步，因为：
        // 1. 需要修改chunk的全局状态
        // 2. 可能需要创建新的chunk
        // 3. 需要更新ChunkList的链表结构
        synchronized (this) {
            allocateNormal(buf, reqCapacity, sizeIdx, cache);
            ++allocationsNormal;  // 更新分配统计
        }
    }

    /**
     * 分配普通内存块
     * 
     * 按照使用率从低到高的顺序尝试分配，这样可以保持内存块的使用率均衡
     * 如果所有现有块都无法分配，则创建新的内存块
     * 
     * 注意：此方法必须在synchronized(this)块中调用
     * 
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     * @param sizeIdx 大小索引
     * @param threadCache 线程缓存
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        if (q050.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q025.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q000.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            qInit.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q075.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
            return;
        }

        // 所有现有块都无法分配，创建新的内存块
        PoolChunk<T> c = newChunk(pageSize, nPSizes, pageShifts, chunkSize);
        boolean success = c.allocate(buf, reqCapacity, sizeIdx, threadCache);
        assert success;
        qInit.add(c);
    }

    /**
     * 增加小内存分配计数
     */
    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    /**
     * 分配大内存块
     * 
     * 大内存块不使用池化机制，直接创建独立的内存块
     * 
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    /**
     * 释放内存
     * 
     * 根据内存块类型选择不同的释放策略：
     * - 非池化内存：直接销毁
     * - 池化内存：尝试放入缓存或释放回池中
     * 
     * @param chunk 内存块
     * @param nioBuffer NIO缓冲区
     * @param handle 内存句柄
     * @param normCapacity 标准化容量
     * @param cache 线程缓存
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            // 非池化内存，直接销毁
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            // 池化内存，尝试缓存或释放
            SizeClass sizeClass = sizeClass(handle);
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // 成功放入缓存，不需要释放
                return;
            }

            freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, false);
        }
    }

    /**
     * 根据句柄判断内存大小类别
     * 
     * @param handle 内存句柄
     * @return 内存大小类别
     */
    private static SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    /**
     * 释放内存块
     * 
     * @param chunk 内存块
     * @param handle 内存句柄
     * @param normCapacity 标准化容量
     * @param sizeClass 大小类别
     * @param nioBuffer NIO缓冲区
     * @param finalizer 是否来自终结器调用
     */
    void freeChunk(PoolChunk<T> chunk, long handle, int normCapacity, SizeClass sizeClass, ByteBuffer nioBuffer,
                   boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // 只有在非终结器调用时才更新统计信息
            // 否则可能因为延迟类加载（如tomcat环境）导致失败
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity, nioBuffer);
        }
        if (destroyChunk) {
            // 销毁内存块不需要在同步锁中执行
            destroyChunk(chunk);
        }
    }

    /**
     * 查找指定大小索引的子页面池头节点
     * 
     * @param sizeIdx 大小索引
     * @return 子页面池头节点
     */
    PoolSubpage<T> findSubpagePoolHead(int sizeIdx) {
        return smallSubpagePools[sizeIdx];
    }

    /**
     * 重新分配ByteBuf的内存
     * 
     * 当需要扩容或缩容时调用此方法，会分配新内存并复制数据
     * 
     * @param buf 要重新分配的ByteBuf
     * @param newCapacity 新的容量大小
     * @param freeOldMemory 是否释放旧内存
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        // 保存旧的内存信息
        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // 分配新内存（不影响读写索引）
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        
        // 复制数据到新内存
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        
        // 释放旧内存
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return 0;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return Collections.emptyList();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    /**
     * 将子页面数组转换为监控指标列表
     * 
     * @param pages 子页面数组
     * @return 监控指标列表
     */
    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    /**
     * 创建新的内存块（池化）
     * 
     * @param pageSize 页面大小
     * @param maxPageIdx 最大页面索引
     * @param pageShifts 页面位移
     * @param chunkSize 块大小
     * @return 新的内存块
     */
    protected abstract PoolChunk<T> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize);
    
    /**
     * 创建新的内存块（非池化）
     * 
     * @param capacity 容量大小
     * @return 新的非池化内存块
     */
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    
    /**
     * 创建新的ByteBuf
     * 
     * @param maxCapacity 最大容量
     * @return 新的PooledByteBuf
     */
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    
    /**
     * 内存复制操作
     * 
     * @param src 源内存
     * @param srcOffset 源偏移量
     * @param dst 目标ByteBuf
     * @param length 复制长度
     */
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    
    /**
     * 销毁内存块
     * 
     * @param chunk 要销毁的内存块
     */
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    /**
     * 将子页面池信息追加到字符串缓冲区
     * 
     * @param buf 字符串缓冲区
     * @param subpages 子页面数组
     */
    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    /**
     * 终结器方法 - 清理资源
     * 
     * 在对象被垃圾回收时调用，确保所有资源被正确释放
     */
    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    /**
     * 销毁所有子页面池
     * 
     * @param pages 子页面数组
     */
    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    /**
     * 销毁所有内存块列表
     * 
     * @param chunkLists 内存块列表数组
     */
    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    /**
     * 堆内存Arena实现
     * 
     * 管理堆内存（byte[]）的分配和释放
     */
    static final class HeapArena extends PoolArena<byte[]> {

        /**
         * 构造堆内存Arena
         * 
         * @param parent 父级分配器
         * @param pageSize 页面大小
         * @param pageShifts 页面位移
         * @param chunkSize 块大小
         * @param directMemoryCacheAlignment 直接内存缓存对齐（堆内存中不使用）
         */
        HeapArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                  int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        /**
         * 创建新的字节数组
         * 
         * @param size 数组大小
         * @return 新的字节数组
         */
        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(
                    this, null, newByteArray(chunkSize), pageSize, pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, null, newByteArray(capacity), capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // 依赖GC回收堆内存
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    /**
     * 直接内存Arena实现
     * 
     * 管理直接内存（ByteBuffer）的分配和释放
     */
    static final class DirectArena extends PoolArena<ByteBuffer> {

        /**
         * 构造直接内存Arena
         * 
         * @param parent 父级分配器
         * @param pageSize 页面大小
         * @param pageShifts 页面位移
         * @param chunkSize 块大小
         * @param directMemoryCacheAlignment 直接内存缓存对齐大小
         */
        DirectArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                    int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxPageIdx,
            int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(chunkSize);
                return new PoolChunk<ByteBuffer>(this, memory, memory, pageSize, pageShifts,
                        chunkSize, maxPageIdx);
            }

            // 需要内存对齐时，分配额外空间用于对齐
            final ByteBuffer base = allocateDirect(chunkSize + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, pageSize,
                    pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(capacity);
                return new PoolChunk<ByteBuffer>(this, memory, memory, capacity);
            }

            // 需要内存对齐时，分配额外空间用于对齐
            final ByteBuffer base = allocateDirect(capacity + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, capacity);
        }

        /**
         * 分配直接内存
         * 
         * @param capacity 容量大小
         * @return 直接内存ByteBuffer
         */
        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner((ByteBuffer) chunk.base);
            } else {
                PlatformDependent.freeDirectBuffer((ByteBuffer) chunk.base);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                // 使用Unsafe进行高性能内存复制
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // 使用NIO Buffer进行内存复制
                // 必须复制NIO缓冲区，因为它们可能被其他Netty缓冲区访问
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
