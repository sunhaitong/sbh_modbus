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
        asyncKafkaSender.sendAsync(kafkaHost, kafkaTopic,vo.getEquipNum(), json)
                .thenAccept(success -> {
                    if (!success) {
                        log.warn("Kafka消息发送失败 - Equip: {}, Topic: {}", vo.getParamNum(), kafkaTopic);
                    } else {
                        log.warn("Kafka unreachable, store to queue");
                        offlineQueue.offer(json);
                    }
                });
    }
}

