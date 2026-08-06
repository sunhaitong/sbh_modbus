package com.chaos.mine.offline;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.*;
import java.util.*;

@Slf4j
@Component
public class OfflineMsgDao {

    private static final String DB_URL = "jdbc:sqlite:/data/sql/offline.db?journal_mode=WAL&synchronous=NORMAL&cache_size=-2000&busy_timeout=5000";

    // 使用连接池优化（单例连接）
    private volatile Connection sharedConnection = null;
    private final Object connectionLock = new Object();

    public OfflineMsgDao() {
        initDatabase();
    }

    private void initDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // 创建表
            stmt.execute("CREATE TABLE IF NOT EXISTS offline_msg (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "topic TEXT NOT NULL," +
                    "msg TEXT NOT NULL," +
                    "create_time LONG NOT NULL)");

            // 创建索引（如果不存在）
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_create_time ON offline_msg(create_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_topic ON offline_msg(topic)");

            // 优化 SQLite 配置
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA cache_size = -2000");  // 2GB 缓存
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA mmap_size = 268435456");  // 256MB
            stmt.execute("PRAGMA busy_timeout = 5000");    // 5秒超时

            log.info("SQLite 数据库初始化完成，WAL模式已启用");

        } catch (Exception e) {
            log.error("SQLite init error", e);
        }
    }

    /**
     * 获取数据库连接（优化单例）
     */
    private Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            synchronized (connectionLock) {
                if (sharedConnection == null || sharedConnection.isClosed()) {
                    sharedConnection = DriverManager.getConnection(DB_URL);
                }
            }
        }
        return sharedConnection;
    }

    /**
     * 批量插入（优化版）- 核心优化点
     */
    public void batchSave(String topic, List<String> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }

        // 监控性能
        long startTime = System.currentTimeMillis();
        int batchSize = msgs.size();

        String sql = "INSERT INTO offline_msg(topic, msg, create_time) VALUES(?,?,?)";

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = getConnection();
            conn.setAutoCommit(false);  // 关键：关闭自动提交

            // 使用预编译语句
            ps = conn.prepareStatement(sql);

            long now = System.currentTimeMillis();

            // 批量添加
            for (String msg : msgs) {
                ps.setString(1, topic);
                ps.setString(2, msg);
                ps.setLong(3, now);
                ps.addBatch();
            }

            // 执行批量插入
            int[] results = ps.executeBatch();

            // 提交事务
            conn.commit();

            long costTime = System.currentTimeMillis() - startTime;

            // 统计成功的插入数量
            int successCount = 0;
            for (int result : results) {
                if (result >= 0) {  // Statement.SUCCESS_NO_INFO
                    successCount++;
                }
            }

            if (successCount != batchSize) {
                log.warn("批量插入部分失败: 成功 {} / 总数 {}，耗时 {}ms",
                        successCount, batchSize, costTime);
            } else if (costTime > 1000) {
                log.warn("批量插入耗时较长: {} 条，耗时 {}ms，速率: {}/秒",
                        batchSize, costTime, (batchSize * 1000L) / (costTime == 0 ? 1 : costTime));
            } else if (batchSize > 100) {
                log.debug("批量插入完成: {} 条，耗时 {}ms", batchSize, costTime);
            }

        } catch (SQLException e) {
            log.error("batchSave SQL异常", e);

            // 事务回滚
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    log.error("事务回滚失败", rollbackEx);
                }
            }

            // 重要：批量保存失败时，抛出异常让上层处理
            throw new RuntimeException("批量保存失败: " + e.getMessage(), e);

        } finally {
            // 只关闭 PreparedStatement，连接保持打开
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException e) {
                    log.warn("关闭 PreparedStatement 失败", e);
                }
            }

            // 重置自动提交
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    log.warn("重置自动提交失败", e);
                }
            }
        }
    }

    /**
     * 批量插入优化版2 - 分批插入，避免超大事务
     */
    public void batchSaveWithChunk(String topic, List<String> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }

        // 如果消息数量太大，分批次插入（避免超大事务）
        int chunkSize = 1000;  // 每批次1000条
        if (msgs.size() <= chunkSize) {
            batchSave(topic, msgs);
            return;
        }

        log.info("大批量消息 {} 条，分批次插入，每批 {} 条", msgs.size(), chunkSize);

        for (int i = 0; i < msgs.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, msgs.size());
            List<String> chunk = msgs.subList(i, end);

            try {
                batchSave(topic, new ArrayList<>(chunk));  // 创建新列表
            } catch (Exception e) {
                log.error("第 {} 批次插入失败，跳过 {} 条消息",
                        (i / chunkSize + 1), chunk.size(), e);
                // 可以选择记录失败的消息，这里简单跳过
            }
        }
    }

    /**
     * 按顺序拉取最旧数据（优化版）
     */
    public List<OfflineMsg> queryOldest(int limit) {
        List<OfflineMsg> list = new ArrayList<>();

        // 使用带索引的查询
        String sql = "SELECT id, topic, msg FROM offline_msg ORDER BY create_time ASC, id ASC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setFetchSize(limit);  // 设置获取大小
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new OfflineMsg(
                            rs.getLong("id"),
                            rs.getString("topic"),
                            rs.getString("msg")
                    ));
                }
            }

            if (log.isDebugEnabled() && !list.isEmpty()) {
                log.debug("查询到 {} 条离线消息，最早ID: {}，最晚ID: {}",
                        list.size(),
                        list.get(0).getId(),
                        list.get(list.size() - 1).getId());
            }

        } catch (Exception e) {
            log.error("queryOldest error", e);
        }

        return list;
    }

    /**
     * 批量删除（优化版）
     */
    public int batchDeleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        // 构建 IN 语句
        StringBuilder sql = new StringBuilder("DELETE FROM offline_msg WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append("?");
            if (i < ids.size() - 1) {
                sql.append(",");
            }
        }
        sql.append(")");

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }

            int deletedCount = ps.executeUpdate();
            log.debug("批量删除了 {} 条离线消息", deletedCount);
            return deletedCount;

        } catch (Exception e) {
            log.error("batchDeleteByIds error", e);
            return 0;
        }
    }
    public void deleteById(long id) {
        String sql = "DELETE FROM offline_msg WHERE id=?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            ps.executeUpdate();

        } catch (Exception e) {
            log.error("deleteById error", e);
        }
    }

    /**
     * 批量删除 - 分批使用IN子句，避免SQL语句过长
     * @param ids 要删除的ID列表
     * @param batchSize 每批最大数量
     */
    public void batchDeleteInBatches(List<Long> ids, int batchSize) {
        if (ids == null || ids.isEmpty()) {
            log.warn("batchDeleteInBatches: ids is empty");
            return;
        }

        // 默认每批1000个
        if (batchSize <= 0) {
            batchSize = 1000;
        }

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);

            int totalDeleted = 0;

            // 分批处理
            for (int i = 0; i < ids.size(); i += batchSize) {
                int end = Math.min(i + batchSize, ids.size());
                List<Long> batchIds = ids.subList(i, end);

                String placeholders = String.join(",", Collections.nCopies(batchIds.size(), "?"));
                String sql = "DELETE FROM offline_msg WHERE id IN (" + placeholders + ")";

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    // 设置参数
                    for (int j = 0; j < batchIds.size(); j++) {
                        ps.setLong(j + 1, batchIds.get(j));
                    }

                    int deleted = ps.executeUpdate();
                    totalDeleted += deleted;

                    // 每批提交一次
                    conn.commit();

                    log.info("batchDeleteInBatches: batch {}-{} deleted {} records",
                            i, end, deleted);
                }
            }

            log.info("batchDeleteInBatches: total deleted {} records", totalDeleted);

        } catch (Exception e) {
            log.error("batchDeleteInBatches error", e);
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                conn.rollback();
            } catch (Exception ex) {
                log.error("rollback error", ex);
            }
        }
    }



    /**
     * 删除一天前的数据（优化版）
     */
    public int deleteBefore(long time) {
        String sql = "DELETE FROM offline_msg WHERE create_time < ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, time);

            // 开始事务
            conn.setAutoCommit(false);
            int deletedCount = ps.executeUpdate();
            conn.commit();
            conn.setAutoCommit(true);

            if (deletedCount > 0) {
                log.info("清理了 {} 条过期离线消息（早于 {}）",
                        deletedCount, new java.util.Date(time));
            }

            return deletedCount;

        } catch (Exception e) {
            log.error("deleteBefore error", e);
            return 0;
        }
    }

    /**
     * 获取统计信息
     */
    public OfflineStats getStats() {
        OfflineStats stats = new OfflineStats();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // 总记录数
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as total FROM offline_msg")) {
                if (rs.next()) {
                    stats.setTotalCount(rs.getLong("total"));
                }
            }

            // 最早和最晚时间
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT MIN(create_time) as earliest, MAX(create_time) as latest FROM offline_msg")) {
                if (rs.next()) {
                    stats.setEarliestTime(rs.getLong("earliest"));
                    stats.setLatestTime(rs.getLong("latest"));
                }
            }

            // 按topic统计
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT topic, COUNT(*) as count FROM offline_msg GROUP BY topic")) {
                while (rs.next()) {
                    stats.addTopicCount(rs.getString("topic"), rs.getLong("count"));
                }
            }

        } catch (Exception e) {
            log.error("getStats error", e);
        }

        return stats;
    }

    @PreDestroy
    public void destroy() {
        if (sharedConnection != null) {
            try {
                sharedConnection.close();
                log.info("SQLite 连接已关闭");
            } catch (SQLException e) {
                log.error("关闭数据库连接失败", e);
            }
        }
    }

    /**
     * 统计信息类
     */
    @Data
    public static class OfflineStats {
        private long totalCount;
        private long earliestTime;
        private long latestTime;
        private Map<String, Long> topicCounts = new HashMap<>();

        public void addTopicCount(String topic, long count) {
            topicCounts.put(topic, count);
        }
    }
}
