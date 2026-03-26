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

            List<String> batch = new ArrayList<>(1000);  // 预分配容量

            while (true) {
                try {
                    // 从队列取出最多 100 条
                    DataService.offlineQueue.drainTo(batch, 1000);

                    if (!batch.isEmpty()) {
                        dao.batchSave(kafkaTopic, batch);
                        batch.clear();
                    }

                    // 动态休眠：队列为空时休眠更久
                    if (batch.isEmpty()) {
                        Thread.sleep(100);  // 缩短休眠时间
                    } else {
                        // 立即处理下一批
                        continue;
                    }

                } catch (Exception e) {
                    log.error("OfflineWriter error", e);
                    try {
                        Thread.sleep(1000);  // 出错后等待更久
                    } catch (InterruptedException ignored) {}
                }
            }

        }, "offline-sqlite-writer").start();
    }


}
