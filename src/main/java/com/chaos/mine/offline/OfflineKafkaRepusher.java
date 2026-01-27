package com.chaos.mine.offline;

import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:50
 * @Version 1.0
 */

@Slf4j
@Component
public class OfflineKafkaRepusher {

    @Autowired
    private OfflineMsgDao dao;

    @Value("${kafka.url}")
    private String kafkaHost;

    @Value("${kafka.topic}")
    private String kafkaTopic;

    @PostConstruct
    public void start() {

        new Thread(() -> {

            while (true) {
                try {
                    List<OfflineMsg> msgs = dao.queryOldest(2000);
                    for (OfflineMsg m : msgs) {

                        try {
                            KafkaUtils.sendSync(kafkaHost, kafkaTopic, m.getMsg());
                            dao.deleteById(m.getId());
                            log.info("Resend OK id={}", m.getId());
                        } catch (Exception e) {
                            log.warn("Kafka not ready, retry later");
                            break;
                        }
                    }

                    Thread.sleep(1000);

                } catch (Exception e) {
                    log.error("Resend loop error", e);
                }
            }

        }, "kafka-repusher").start();
    }
}

