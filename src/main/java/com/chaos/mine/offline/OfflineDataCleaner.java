package com.chaos.mine.offline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:53
 * @Version 1.0
 */

@Slf4j
@Component
public class OfflineDataCleaner {

    @Autowired
    private OfflineMsgDao dao;

    @Value("${offline.cache.day:1}")
    private Long offlineCacheDay;

    @PostConstruct
    public void start() {

        new Thread(() -> {

            while (true) {
                try {
                    long expire = System.currentTimeMillis() - offlineCacheDay * 24 * 3600 * 1000L;
                    int count = dao.deleteBefore(expire);

                    if (count > 0) {
                        log.info("Cleaned {} expired records", count);
                    }

                    Thread.sleep(3600 * 1000);

                } catch (Exception e) {
                    log.error("Cleaner error", e);
                }
            }

        }, "offline-cleaner").start();
    }
}
