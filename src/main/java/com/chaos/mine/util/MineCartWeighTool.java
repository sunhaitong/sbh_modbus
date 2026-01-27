package com.chaos.mine.util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 矿车称重数据处理工具类
 * 功能：
 * 1. 按阈值缓存称重数据（>2t开始，<2t停止）
 * 2. 剔除前后20%数据后计算均值，估算真实重量
 * 3. 内存保护：最大缓存容量+超时自动清理
 * 4. 支持多矿车独立处理（通过矿车ID区分）
 */
public final class MineCartWeighTool {
    // ===================== 可配置常量（可根据业务调整） =====================
    /** 开始/停止缓存的重量阈值(t) */
    public static final double WEIGHT_THRESHOLD = 2.0;
    /** 最大缓存容量（1小时数据，防止内存溢出） */
    public static final int MAX_CACHE_SIZE = 3600;
    /** 缓存超时时间（2h） */
    public static final long CACHE_TIMEOUT_MS = 2 * 60 * 60 * 1000;
    /** 剔除前后数据的百分比 */
    public static final double TRIM_PERCENT = 0.2;
    /** 定时清理间隔（分钟） */
    private static final int CLEAN_INTERVAL_MINUTES = 1;

    // ===================== 内部状态管理 =====================
    // 矿车状态枚举（内部使用）
    private enum WeighState { IDLE, CACHING, COMPLETED }

    // 矿车称重上下文（每个矿车独立的状态和缓存）
    private static class CartContext {
        WeighState state = WeighState.IDLE;
        List<Double> weightCache = new ArrayList<>();
        long cacheStartTime = 0;
    }

    // 多矿车上下文存储（线程安全的Map）
    private static final ConcurrentHashMap<String, CartContext> CART_CONTEXT_MAP = new ConcurrentHashMap<>();
    // 定时清理线程池（全局唯一）
    private static final ScheduledExecutorService CLEAN_SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "mine-cart-weigh-clean-thread");
                t.setDaemon(true); // 守护线程，不阻塞程序退出
                return t;
            }
    );

    // 静态初始化：启动定时清理任务
    static {
        CLEAN_SCHEDULER.scheduleAtFixedRate(MineCartWeighTool::cleanTimeoutCaches,
                0, CLEAN_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // 私有构造方法：禁止实例化工具类
    private MineCartWeighTool() {}

    // ===================== 核心对外方法 =====================

    /**
     * 处理单条称重数据（核心方法）
     * @param cartId 矿车唯一标识（区分不同矿车）
     * @param weight 采集的重量（单位：t）
     */
    public static void processWeight(String cartId, double weight) {
        // 参数校验
        if (cartId == null || cartId.trim().isEmpty() || weight < 0) {
            return;
        }

        // 获取/创建矿车上下文（线程安全）
        CartContext context = CART_CONTEXT_MAP.computeIfAbsent(cartId, k -> new CartContext());

        // 同步处理，保证线程安全
        synchronized (context) {
            switch (context.state) {
                case IDLE:
                    // 重量超过阈值，开始缓存
                    if (weight > WEIGHT_THRESHOLD) {
                        context.state = WeighState.CACHING;
                        context.cacheStartTime = System.currentTimeMillis();
                        context.weightCache.add(weight);
                    }
                    break;

                case CACHING:
                    // 1. 缓存达到最大容量，停止缓存
                    if (context.weightCache.size() >= MAX_CACHE_SIZE) {
                        context.state = WeighState.COMPLETED;
                        break;
                    }

                    // 2. 重量低于阈值，停止缓存
                    if (weight < WEIGHT_THRESHOLD) {
                        context.state = WeighState.COMPLETED;
                        break;
                    }

                    // 3. 继续缓存数据
                    context.weightCache.add(weight);
                    break;

                case COMPLETED:
                    // 缓存已完成，等待计算后重置
                    break;
            }
        }
    }

    /**
     * 计算矿车的真实重量（剔除前后20%数据后取均值）
     * @param cartId 矿车唯一标识
     * @return 真实重量（t），无有效数据返回0.0
     */
    public static double calculateRealWeight(String cartId) {
        // 参数校验
        if (cartId == null || cartId.trim().isEmpty()) {
            return 0.0;
        }

        CartContext context = CART_CONTEXT_MAP.get(cartId);
        if (context == null || context.state != WeighState.COMPLETED || context.weightCache.isEmpty()) {
            return 0.0;
        }

        // 同步计算，避免并发问题
        synchronized (context) {
            // 1. 排序数据
            List<Double> sortedData = new ArrayList<>(context.weightCache);
            Collections.sort(sortedData);

            // 2. 计算剔除数量（避免剔除后无数据）
            int totalSize = sortedData.size();
            int trimCount = (int) Math.round(totalSize * TRIM_PERCENT);
            trimCount = Math.min(trimCount, totalSize / 2 - 1);

            // 3. 截取中间数据并计算均值
            List<Double> filteredData = sortedData.subList(trimCount, totalSize - trimCount);
            double sum = filteredData.stream().mapToDouble(Double::doubleValue).sum();
            double average = sum / filteredData.size();

            // 4. 重置当前矿车的上下文（准备下一次采集）
            resetCartContext(context);

            return average;
        }
    }

    /**
     * 手动清理指定矿车的缓存数据
     * @param cartId 矿车唯一标识
     */
    public static void clearCartCache(String cartId) {
        if (cartId == null || cartId.trim().isEmpty()) {
            return;
        }
        CartContext context = CART_CONTEXT_MAP.get(cartId);
        if (context != null) {
            synchronized (context) {
                resetCartContext(context);
            }
        }
    }

    /**
     * 关闭工具类（程序退出时调用，释放线程池）
     */
    public static void shutdown() {
        CLEAN_SCHEDULER.shutdown();
        try {
            if (!CLEAN_SCHEDULER.awaitTermination(1, TimeUnit.MINUTES)) {
                CLEAN_SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            CLEAN_SCHEDULER.shutdownNow();
        }
        CART_CONTEXT_MAP.clear();
    }

    // ===================== 内部辅助方法 =====================

    /**
     * 重置矿车上下文
     */
    private static void resetCartContext(CartContext context) {
        context.state = WeighState.IDLE;
        context.weightCache.clear();
        context.cacheStartTime = 0;
    }

    /**
     * 清理所有超时的缓存数据
     */
    private static void cleanTimeoutCaches() {
        long now = System.currentTimeMillis();
        // 遍历所有矿车上下文，清理超时的缓存
        CART_CONTEXT_MAP.forEach((cartId, context) -> {
            synchronized (context) {
                if (context.state == WeighState.CACHING && (now - context.cacheStartTime) > CACHE_TIMEOUT_MS) {
                    resetCartContext(context);
                }
            }
        });
    }

    // ===================== 测试示例 =====================
    /*public static void main(String[] args) throws InterruptedException {
        String cartId = "MINECART_001";

        // 模拟称重数据采集（装卸过程）
        // 1. 重量上升（开始缓存）
        for (int i = 0; i < 50; i++) {
            double weight = 1.0 + i * 0.1; // 1t → 6t
            MineCartWeighTool.processWeight(cartId, weight);
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // 2. 重量稳定（5t左右波动）
        for (int i = 0; i < 100; i++) {
            double weight = 5.0 + Math.random() * 0.2 - 0.1;
            MineCartWeighTool.processWeight(cartId, weight);
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // 3. 重量下降（停止缓存）
        for (int i = 0; i < 50; i++) {
            double weight = 6.0 - i * 0.12; // 6t → 0t
            MineCartWeighTool.processWeight(cartId, weight);
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // 计算真实重量
        double realWeight = MineCartWeighTool.calculateRealWeight(cartId);
        System.out.printf("矿车[%s]的真实重量：%.2f t%n", cartId, realWeight);

        // 程序退出前关闭工具类
        MineCartWeighTool.shutdown();
    }*/

}