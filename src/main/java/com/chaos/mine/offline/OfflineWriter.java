package com.chaos.mine.offline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:49
 * @Version 1.0
 */

@Slf4j
@Component
public class OfflineWriter {

    @Autowired
    private OfflineMsgDao dao;

    @Value("${kafka.topic}")
    private String kafkaTopic;

    @PostConstruct
    public void start() {

        new Thread(() -> {

            List<String> batch = new ArrayList<>();

            while (true) {
                try {
                    // 从队列取出最多 100 条
                    DataService.offlineQueue.drainTo(batch, 100);

                    if (!batch.isEmpty()) {
                        dao.batchSave(kafkaTopic, batch);
                        batch.clear();
                    }

                    Thread.sleep(200); // 每 200ms 刷一次

                } catch (Exception e) {
                    log.error("OfflineWriter error", e);
                }
            }

        }, "offline-sqlite-writer").start();
    }
}
