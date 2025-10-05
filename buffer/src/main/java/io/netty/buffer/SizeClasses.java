/*
 * Copyright 2020 The Netty Project
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

import static io.netty.buffer.PoolThreadCache.*;

/**
 * 大小类别管理器 - 用于管理内存池中不同大小的内存块分类
 * 
 * SizeClasses 需要在包含之前定义 pageShifts 参数，它定义了以下内容：
 * 
 * LOG2_SIZE_CLASS_GROUP: 每个大小翻倍时大小类别数量的对数值
 * 
 * LOG2_MAX_LOOKUP_SIZE: 查找表中最大大小类别的对数值
 * 
 * sizeClasses: 完整的大小类别表，包含以下元组：
 *              [index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
 * 
 *     index: 大小类别索引
 *     
 *     log2Group: 组基础大小的对数值（不包含增量）
 *     
 *     log2Delta: 与前一个大小类别的增量对数值
 *     
 *     nDelta: 增量乘数
 *     
 *     isMultiPageSize: 如果是页面大小的倍数则为'yes'，否则为'no'
 *     
 *     isSubPage: 如果是子页面大小类别则为'yes'，否则为'no'
 *     
 *     log2DeltaLookup: 如果是查找表大小类别则与log2Delta相同，否则为'no'
 * 
 * nSubpages: 子页面大小类别的数量
 * 
 * nSizes: 大小类别的总数量
 * 
 * nPSizes: 页面大小倍数的大小类别数量
 * 
 * smallMaxSizeIdx: 最大小型大小类别索引
 * 
 * lookupMaxclass: 查找表中包含的最大大小类别
 * 
 * log2NormalMinClass: 最小正常大小类别的对数值
 * 
 * 第一个大小类别和间距是 1 << LOG2_QUANTUM
 * 
 * 每个组有 1 << LOG2_SIZE_CLASS_GROUP 个大小类别
 * 
 * 大小计算公式: size = 1 << log2Group + nDelta * (1 << log2Delta)
 * 
 * 第一个大小类别有特殊的编码，因为大小必须在组和增量*nDelta之间分割
 * 
 * 如果 pageShift = 13，sizeClasses 看起来像这样：
 * 
 * (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * 
 * ( 0,     4,        4,         0,       no,             yes,        4)
 * ( 1,     4,        4,         1,       no,             yes,        4)
 * ( 2,     4,        4,         2,       no,             yes,        4)
 * ( 3,     4,        4,         3,       no,             yes,        4)
 * 
 * ( 4,     6,        4,         1,       no,             yes,        4)
 * ( 5,     6,        4,         2,       no,             yes,        4)
 * ( 6,     6,        4,         3,       no,             yes,        4)
 * ( 7,     6,        4,         4,       no,             yes,        4)
 * 
 * ( 8,     7,        5,         1,       no,             yes,        5)
 * ( 9,     7,        5,         2,       no,             yes,        5)
 * ( 10,    7,        5,         3,       no,             yes,        5)
 * ( 11,    7,        5,         4,       no,             yes,        5)
 * ...
 * ...
 * ( 72,    23,       21,        1,       yes,            no,        no)
 * ( 73,    23,       21,        2,       yes,            no,        no)
 * ( 74,    23,       21,        3,       yes,            no,        no)
 * ( 75,    23,       21,        4,       yes,            no,        no)
 * 
 * ( 76,    24,       22,        1,       yes,            no,        no)
 */
abstract class SizeClasses implements SizeClassesMetric {

    // 量子大小的对数值，用于定义最小分配单位 (16字节)
    static final int LOG2_QUANTUM = 4;

    // 每个大小组中大小类别数量的对数值 (每组4个大小类别)
    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    
    // 查找表中最大大小的对数值 (4KB)
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    // 大小类别数组中各字段的索引位置
    private static final int INDEX_IDX = 0;          // 索引字段位置
    
    private static final int LOG2GROUP_IDX = 1;      // 组对数字段位置
    
    private static final int LOG2DELTA_IDX = 2;      // 增量对数字段位置
    
    private static final int NDELTA_IDX = 3;         // 增量倍数字段位置
    
    private static final int PAGESIZE_IDX = 4;       // 页面大小标志字段位置
    
    private static final int SUBPAGE_IDX = 5;        // 子页面标志字段位置
    
    private static final int LOG2_DELTA_LOOKUP_IDX = 6; // 查找表增量对数字段位置

    // 布尔值的字节表示
    private static final byte no = 0, yes = 1;

    /**
     * 构造函数 - 初始化大小类别管理器
     * 
     * @param pageSize 页面大小（字节）
     * @param pageShifts 页面大小的对数值
     * @param chunkSize 块大小（字节）
     * @param directMemoryCacheAlignment 直接内存缓存对齐大小
     */
    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        // 保存基本配置参数
        this.pageSize = pageSize;
        
        this.pageShifts = pageShifts;
        
        this.chunkSize = chunkSize;
        
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        // 计算需要的组数量
        int group = log2(chunkSize) + 1 - LOG2_QUANTUM;

        // 生成大小类别表
        // [index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];
        
        // 生成所有大小类别并返回总数量
        nSizes = sizeClasses();

        // 生成查找表
        sizeIdx2sizeTab = new int[nSizes];
        
        pageIdx2sizeTab = new int[nPSizes];
        
        // 初始化索引到大小的映射表
        idx2SizeTab(sizeIdx2sizeTab, pageIdx2sizeTab);

        // 生成大小到索引的快速查找表
        size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        
        size2idxTab(size2idxTab);
    }

    // 页面大小（字节）
    protected final int pageSize;
    
    // 页面大小的对数值
    protected final int pageShifts;
    
    // 块大小（字节）
    protected final int chunkSize;
    
    // 直接内存缓存对齐大小
    protected final int directMemoryCacheAlignment;

    // 大小类别总数
    final int nSizes;
    
    // 子页面大小类别数量
    int nSubpages;
    
    // 页面大小倍数的大小类别数量
    int nPSizes;

    // 最大小型大小类别索引
    int smallMaxSizeIdx;

    // 查找表支持的最大大小
    private int lookupMaxSize;

    // 大小类别定义表
    private final short[][] sizeClasses;

    // 页面索引到大小的映射表
    private final int[] pageIdx2sizeTab;

    // 大小索引到实际大小的查找表（用于 sizeIdx <= smallMaxSizeIdx）
    private final int[] sizeIdx2sizeTab;

    // 大小到索引的查找表（用于 size <= lookupMaxclass）
    // 间距是 1 << LOG2_QUANTUM，所以数组大小是 lookupMaxclass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    /**
     * 生成所有大小类别
     * 
     * @return 大小类别的总数量
     */
    private int sizeClasses() {
        int normalMaxSize = -1;

        int index = 0;
        
        int size = 0;

        int log2Group = LOG2_QUANTUM;
        
        int log2Delta = LOG2_QUANTUM;
        
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        // 第一个小组，nDelta 从 0 开始
        // 第一个大小类别是 1 << LOG2_QUANTUM
        int nDelta = 0;
        
        while (nDelta < ndeltaLimit) {
            size = sizeClass(index++, log2Group, log2Delta, nDelta++);
        }
        
        log2Group += LOG2_SIZE_CLASS_GROUP;

        // 所有剩余的组，nDelta 从 1 开始
        while (size < chunkSize) {
            nDelta = 1;

            while (nDelta <= ndeltaLimit && size < chunkSize) {
                size = sizeClass(index++, log2Group, log2Delta, nDelta++);
                
                normalMaxSize = size;
            }

            log2Group++;
            
            log2Delta++;
        }

        // chunkSize 必须等于 normalMaxSize
        assert chunkSize == normalMaxSize;

        // 返回大小索引的数量
        return index;
    }

    /**
     * 计算并设置单个大小类别
     * 
     * @param index 大小类别索引
     * @param log2Group 组大小的对数值
     * @param log2Delta 增量的对数值
     * @param nDelta 增量倍数
     * @return 计算出的大小值
     */
    private int sizeClass(int index, int log2Group, int log2Delta, int nDelta) {
        short isMultiPageSize;
        
        // 判断是否为页面大小的倍数
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            
            int size = (1 << log2Group) + (1 << log2Delta) * nDelta;

            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        // 计算 nDelta 的对数值
        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        // 判断是否需要移除（当 2^log2Ndelta < nDelta 时）
        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        // 计算大小的对数值
        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        
        if (log2Size == log2Group) {
            remove = yes;
        }

        // 判断是否为子页面大小
        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        // 计算查找表中的增量对数值
        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        // 创建大小类别数组
        short[] sz = {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };

        // 保存到大小类别表中
        sizeClasses[index] = sz;
        
        // 计算实际大小
        int size = (1 << log2Group) + (nDelta << log2Delta);

        // 更新统计计数器
        if (sz[PAGESIZE_IDX] == yes) {
            nPSizes++;
        }
        
        if (sz[SUBPAGE_IDX] == yes) {
            nSubpages++;
            
            smallMaxSizeIdx = index;
        }
        
        if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
            lookupMaxSize = size;
        }
        
        return size;
    }

    /**
     * 初始化索引到大小的映射表
     * 
     * @param sizeIdx2sizeTab 大小索引到大小的映射表
     * @param pageIdx2sizeTab 页面索引到大小的映射表
     */
    private void idx2SizeTab(int[] sizeIdx2sizeTab, int[] pageIdx2sizeTab) {
        int pageIdx = 0;

        // 遍历所有大小类别
        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            
            int log2Group = sizeClass[LOG2GROUP_IDX];
            
            int log2Delta = sizeClass[LOG2DELTA_IDX];
            
            int nDelta = sizeClass[NDELTA_IDX];

            // 计算实际大小
            int size = (1 << log2Group) + (nDelta << log2Delta);
            
            sizeIdx2sizeTab[i] = size;

            // 如果是页面大小的倍数，也添加到页面映射表中
            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = size;
            }
        }
    }

    /**
     * 初始化大小到索引的快速查找表
     * 
     * @param size2idxTab 大小到索引的查找表
     */
    private void size2idxTab(int[] size2idxTab) {
        int idx = 0;
        
        int size = 0;

        // 为每个大小类别填充查找表
        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            
            // 计算当前大小类别需要填充的次数
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
    }

    /**
     * 根据大小索引获取实际大小（查表方式）
     * 
     * @param sizeIdx 大小索引
     * @return 对应的实际大小
     */
    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    /**
     * 根据大小索引计算实际大小（计算方式）
     * 
     * @param sizeIdx 大小索引
     * @return 对应的实际大小
     */
    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        // 计算组号和组内偏移
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        // 计算组的基础大小
        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        // 计算增量位移
        int shift = group == 0? 1 : group;
        
        int lgDelta = shift + LOG2_QUANTUM - 1;
        
        // 计算组内偏移大小
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    /**
     * 根据页面索引获取页面大小（查表方式）
     * 
     * @param pageIdx 页面索引
     * @return 对应的页面大小
     */
    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    /**
     * 根据页面索引计算页面大小（计算方式）
     * 
     * @param pageIdx 页面索引
     * @return 对应的页面大小
     */
    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        // 计算组号和组内偏移
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        // 计算组的基础大小
        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        // 计算增量位移
        int shift = group == 0? 1 : group;
        
        int log2Delta = shift + pageShifts - 1;
        
        // 计算组内偏移大小
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    /**
     * 根据请求大小找到对应的大小索引
     * 
     * @param size 请求的大小
     * @return 对应的大小索引
     */
    @Override
    public int size2SizeIdx(int size) {
        // 大小为0时返回索引0
        if (size == 0) {
            return 0;
        }
        
        // 超过块大小时返回最大索引
        if (size > chunkSize) {
            return nSizes;
        }

        // 如果需要内存对齐，先进行对齐
        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        // 小于等于查找表最大大小时，使用查找表
        if (size <= lookupMaxSize) {
            // (size-1) / MIN_TINY
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }

        // 大于查找表范围时，使用计算方式
        int x = log2((size << 1) - 1);
        
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        
        int mod = (size - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        return group + mod;
    }

    /**
     * 根据页面数量获取页面索引
     * 
     * @param pages 页面数量
     * @return 对应的页面索引
     */
    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    /**
     * 根据页面数量获取页面索引（向下取整）
     * 
     * @param pages 页面数量
     * @return 对应的页面索引（向下取整）
     */
    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    /**
     * 计算页面数量对应的页面索引
     * 
     * @param pages 页面数量
     * @param floor 是否向下取整
     * @return 对应的页面索引
     */
    private int pages2pageIdxCompute(int pages, boolean floor) {
        // 计算总的页面大小
        int pageSize = pages << pageShifts;
        
        // 超过块大小时返回最大页面索引
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        // 如果需要向下取整且当前索引对应的大小大于请求大小，则减1
        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    /**
     * 将大小向上舍入到对齐边界的最近倍数
     * 
     * @param size 原始大小
     * @return 对齐后的大小
     */
    private int alignSize(int size) {
        int delta = size & directMemoryCacheAlignment - 1;
        
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    /**
     * 将请求大小标准化为实际分配大小
     * 
     * @param size 请求的大小
     * @return 标准化后的大小
     */
    @Override
    public int normalizeSize(int size) {
        // 大小为0时返回最小大小
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        
        // 如果需要内存对齐，先进行对齐
        if (directMemoryCacheAlignment > 0) {
            size = alignSize(size);
        }

        // 小于等于查找表最大大小时，使用查找表
        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            
            assert ret == normalizeSizeCompute(size);
            
            return ret;
        }
        
        // 大于查找表范围时，使用计算方式
        return normalizeSizeCompute(size);
    }

    /**
     * 计算标准化大小（计算方式）
     * 
     * @param size 请求的大小
     * @return 标准化后的大小
     */
    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
                
        int delta = 1 << log2Delta;
        
        int delta_mask = delta - 1;
        
        return size + delta_mask & ~delta_mask;
    }
}
