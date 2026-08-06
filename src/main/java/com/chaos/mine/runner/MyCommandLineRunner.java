package com.chaos.mine.runner;

import com.chaos.mine.service.CanWeightReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * @author sunht
 * @date 2021/8/31
 */

@Slf4j
@Component
public class MyCommandLineRunner implements CommandLineRunner {
    @Autowired
    private CanWeightReader canWeightReader;

    @Override
    public void run(String... args) {

        try {
            Connection conn = null;
            Statement stmt = null;

            try {

                // 1. 加载驱动
                Class.forName("org.sqlite.JDBC");

                // 2. 数据库目录
                String dbDir = "/data/sql";

                // Windows测试可改:
                // String dbDir = "D:/sqlite";

                // 3. 自动创建目录
                File dir = new File(dbDir);

                if (!dir.exists()) {

                    boolean ok = dir.mkdirs();

                    System.out.println("创建目录: " + ok);
                }

                // 4. SQLite路径
                String dbPath = dbDir + "/offline.db";

                // 5. JDBC URL
                String url = "jdbc:sqlite:" + dbPath;

                System.out.println("数据库路径: " + dbPath);

                // 6. 连接数据库（不存在会自动创建）
                conn = DriverManager.getConnection(url);

                System.out.println("SQLite连接成功");

                stmt = conn.createStatement();

                // 7. 开启 WAL
                stmt.execute("PRAGMA journal_mode=WAL;");

                // 8. 提高性能
                stmt.execute("PRAGMA synchronous=NORMAL;");

                // 9. 限制 WAL 大小
                stmt.execute("PRAGMA journal_size_limit=10485760;");

                // 10. 创建表
                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS offline_msg (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "topic TEXT," +
                                "msg TEXT," +
                                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP" +
                                ");"
                );

                System.out.println("SQLite初始化完成");

            } catch (Exception e) {

                e.printStackTrace();

            } finally {

                try {

                    if (stmt != null) {
                        stmt.close();
                    }

                    if (conn != null) {
                        conn.close();
                    }

                } catch (Exception e) {

                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        DataConfigManager.getInstance().loadDataStandardConfig();
        canWeightReader.read();

        // 初始化音量
        try {
            Process p1 = new ProcessBuilder(
                    "amixer", "-c", "0", "sset", "PCM", "100%"
            ).start();
            p1.waitFor();
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }


}
