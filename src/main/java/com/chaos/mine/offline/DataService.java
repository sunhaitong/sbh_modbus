package com.chaos.mine.offline;



import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.AsyncKafkaSender;
import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AsyncKafkaSender asyncKafkaSender;


    public void sendMsg2Kafka(DeviceDataVO vo) {
        String json = JSON.toJSONString(vo);
        String key = vo.getEquipNum();
        boolean res = asyncKafkaSender.sendWithTimeout(kafkaHost, kafkaTopic, key, json);
        if (!res) {
            boolean offerSuccess = offlineQueue.offer(json);
            if (!offerSuccess) {
                log.error("离线队列已满，消息丢失: {}", json.substring(0, Math.min(json.length(), 100)));
            }
        }
    }

    public void batchSendMsg2Kafka(boolean cacheFlag, String topic, String key, String msg) {
        if (topic == null || topic.isEmpty()) {
            topic = kafkaTopic;
        }
        boolean res = asyncKafkaSender.sendWithTimeout(kafkaHost, topic, key, msg);
        if (cacheFlag && !res) {
            boolean offerSuccess = offlineQueue.offer(msg);
            if (!offerSuccess) {
                log.error("离线队列已满，消息丢失: {}");
            }
        }
    }
}

