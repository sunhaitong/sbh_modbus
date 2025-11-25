package com.chaos.mine.offline;



import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    public void sendMsg2Kafka(DeviceDataVO vo) {
        String json = JSON.toJSONString(vo);

        try {
            KafkaUtils.sendSync(kafkaHost, kafkaTopic, json);
        } catch (Exception e) {
            log.warn("Kafka unreachable, store to queue");
            offlineQueue.offer(json);                       // ② Kafka 不可用 → 入队
        }
    }
}

