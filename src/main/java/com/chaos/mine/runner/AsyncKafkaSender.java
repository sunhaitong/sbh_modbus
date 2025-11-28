package com.chaos.mine.runner;

import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/22 23:31
 * @Version 1.0
 */
@Component
@Slf4j
public class AsyncKafkaSender {

    private final ExecutorService kafkaExecutor = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.DiscardPolicy() // Kafka不通时直接丢弃，不阻塞业务线程
    );

    public CompletableFuture<Boolean> sendAsync(String kafkaHost, String topic, String key, String message) {
        return CompletableFuture.supplyAsync(() -> {
            return sendWithTimeout(kafkaHost, topic, key, message);
        }, kafkaExecutor);
    }

    private boolean sendWithTimeout(String kafkaHost, String topic, String key, String message) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                KafkaUtils.send(kafkaHost, topic, key, message);
                return true;
            } catch (Exception e) {
                log.error("Kafka发送失败 - Host: {}, Topic: {}, Error: {}", kafkaHost, topic, e.getMessage());
                return false;
            }
        });

        try {
            // 设置5秒超时
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Kafka发送超时 - Host: {}, Topic: {}", kafkaHost, topic);
            future.cancel(true); // 取消任务
            return false;
        } catch (Exception e) {
            log.error("Kafka发送异常", e);
            return false;
        }
    }
}