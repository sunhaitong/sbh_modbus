package com.chaos.mine.runner;

import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
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

    public boolean sendWithTimeout(String kafkaHost, String topic, String key, String message) {
        Future<RecordMetadata> future = null;
        try {
            future = KafkaUtils.send(kafkaHost, topic, key, message);

            // 设置2秒超时
            RecordMetadata metadata = future.get(2, TimeUnit.SECONDS);

            // 验证发送结果
            if (metadata != null) {
                log.debug("发送成功 - Partition: {}, Offset: {}",
                        metadata.partition(), metadata.offset());
                return true;
            }
            return false;

        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            log.warn("Kafka发送超时（5秒）");
            return false;

        }
    }
}