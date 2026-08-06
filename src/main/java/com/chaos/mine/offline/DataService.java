package com.chaos.mine.offline;
;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:47
 * @Version 1.0
 */

@Slf4j
@Service
public class DataService {

    public static final BlockingQueue<String> offlineQueue = new LinkedBlockingQueue<>(50000);

    @Value("${kafka.url}")
    private String kafkaHost;

    @Value("${kafka.topic}")
    private String kafkaTopic;

    public void batchSendMsg2Kafka(boolean cacheFlag, String topic, String key, String msg) {
        if (topic == null || topic.isEmpty()) {
            topic = kafkaTopic;
        }
        // boolean res = asyncKafkaSender.sendWithTimeout(kafkaHost, topic, key, msg);
        if (DataConfigManager.getInstance().isOlineStatus()) {
            boolean res = KafkaUtils.send(kafkaHost, topic, key, msg);
            if (cacheFlag && !res) {
                boolean offerSuccess = offlineQueue.offer(msg);
                if (!offerSuccess) {
                    log.error("离线队列已满，消息丢失: {}");
                }
            }
        } else {
            boolean offerSuccess = offlineQueue.offer(msg);
            if (!offerSuccess) {
                log.error("离线队列已满，消息丢失: {}");
            }
        }

    }
}

