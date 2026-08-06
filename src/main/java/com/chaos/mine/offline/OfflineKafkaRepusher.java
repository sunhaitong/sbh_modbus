package com.chaos.mine.offline;

import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.KafkaUtils;
import com.chaos.mine.util.WavPlayer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:50
 * @Version 1.0
 */

@Slf4j
@Component
public class OfflineKafkaRepusher {

    @Value("${video.path.net.connect:}")
    private String netConnectVideoPath;

    @Value("${video.path.data.send:}")
    private String sendDataVideoPath;

    @Autowired
    private OfflineMsgDao dao;

    @Value("${kafka.url}")
    private String kafkaHost;

    @Value("${kafka.topic}")
    private String kafkaTopic;

    private volatile int dataCount;

    @PostConstruct
    public void start() {

        new Thread(() -> {
            while (true) {
                try {
                    if (!DataConfigManager.getInstance().isOlineStatus()) {
                        Thread.sleep(1000);
                        continue;
                    }
                    List<OfflineMsg> msgs = dao.queryOldest(2000);
                    dataCount = msgs.size();

                    if (CollectionUtils.isNotEmpty(msgs)) {
                        List<String> messages = msgs.stream()
                                .map(OfflineMsg::getMsg)
                                .collect(Collectors.toList());
                        List<Long> ids = msgs.stream()
                                .map(OfflineMsg::getId)
                                .collect(Collectors.toList());

                        long startTime = System.currentTimeMillis();

                        // 使用优化后的批量发送
                        boolean res = KafkaUtils.sendBatchAsync(kafkaHost, kafkaTopic, messages);

                        if (res) {
                            dao.batchDeleteInBatches(ids, 1000);
                            log.info("Resend OK count = {}, cost = {}ms",
                                    messages.size(),
                                    System.currentTimeMillis() - startTime);
                        } else {
                            log.warn("Kafka not ready, retry later");
                            // 失败时等待更长一点
                            Thread.sleep(5000);
                        }
                    }



                } catch (Exception e) {
                    log.error("Resend loop error", e);
                }
            }
        }, "kafka-repusher").start();
    }

    // 每10秒读取一次
    @Scheduled(fixedRate = 10000)
    @Async
    public void checkNetwork() {
        if (!ping()) {
            //log.info("Check network not OK");
            DataConfigManager.getInstance().setOlineStatus(false);
        } else {
            //log.info("Check network OK");
            DataConfigManager.getInstance().setOlineStatus(true);
            if (dataCount > 0) {
                // 正在传输数据
                WavPlayer.playWav(netConnectVideoPath);
            } else {
                // 数据传输完成
                WavPlayer.playWav(sendDataVideoPath);
            }
        }
    }

    public boolean ping() {
        try {
            InetAddress address = InetAddress.getByName(kafkaHost.substring(0, kafkaHost.indexOf(":")));
            return address.isReachable(1000);
        } catch (IOException e) {
            return false;
        }
    }
}

