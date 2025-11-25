package com.chaos.mine.offline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:51
 * @Version 1.0
 */

@Slf4j
@Component
public class OfflineMsgDao {

    private static final String DB_URL = "jdbc:sqlite:/opt/app/data/offline.db";

    public OfflineMsgDao() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS offline_msg (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "topic TEXT NOT NULL," +
                    "msg TEXT NOT NULL," +
                    "create_time LONG NOT NULL)");

        } catch (Exception e) {
            log.error("SQLite init error", e);
        }
    }

    // 批量插入（高性能）
    public void batchSave(String topic, List<String> msgs) {
        String sql = "INSERT INTO offline_msg(topic, msg, create_time) VALUES(?,?,?)";

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                long now = System.currentTimeMillis();

                for (String msg : msgs) {
                    ps.setString(1, topic);
                    ps.setString(2, msg);
                    ps.setLong(3, now);
                    ps.addBatch();
                }

                ps.executeBatch();
            }

            conn.commit();

        } catch (Exception e) {
            log.error("batchSave error", e);
        }
    }

    // 按顺序拉取最旧数据
    public List<OfflineMsg> queryOldest(int limit) {
        List<OfflineMsg> list = new ArrayList<>();

        String sql = "SELECT id, topic, msg FROM offline_msg ORDER BY id ASC LIMIT ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                list.add(new OfflineMsg(
                        rs.getLong("id"),
                        rs.getString("topic"),
                        rs.getString("msg")
                ));
            }

        } catch (Exception e) {
            log.error("queryOldest error", e);
        }

        return list;
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

    // 删除一天前的数据
    public int deleteBefore(long time) {
        String sql = "DELETE FROM offline_msg WHERE create_time < ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, time);
            return ps.executeUpdate();

        } catch (Exception e) {
            log.error("deleteBefore error", e);
            return 0;
        }
    }
}
