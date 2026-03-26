package com.chaos.mine.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * kafka工具类
 *
 * @author sunht
 * @date 2021/12/15
 */
// @Component
@Slf4j
public class KafkaUtils {

    /**
     * 生产者缓存
     */
    private static final Map<String, KafkaProducer<String, String>> producerCache = new ConcurrentHashMap<>();

    private static KafkaProducer<String, String> createProducer(String brokers) {
        Properties prop = new Properties();
        prop.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        prop.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        prop.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // ★★★ 核心优化配置
        prop.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);      // 16KB 批处理大小
        prop.put(ProducerConfig.LINGER_MS_CONFIG, 5);           // 等待5ms凑批
        prop.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB 缓冲区
        prop.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // 压缩

        // 超时配置（用于快速失败）
        prop.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);     // 最大阻塞时间
        prop.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        prop.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);

        // 重试和确认
        prop.put(ProducerConfig.RETRIES_CONFIG, 0);             // 不重试（快速失败）
        prop.put(ProducerConfig.ACKS_CONFIG, "1");              // leader确认

        return new KafkaProducer<>(prop);
    }
    /**
     * 关闭
     */
    static {
        // jvm关闭钩子，优雅关闭虚拟机
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> producerCache.forEach(KafkaUtils::accept)
            )
        );
    }


    /**
     * 获取生产者
     *
     * @param brokers
     * @return
     */
    private static KafkaProducer<String, String> getProducer(String brokers) {
        return producerCache.compute(brokers, (k, oldProducer) -> {
            if (oldProducer == null) {
                oldProducer = createProducer(brokers);
            }
            return oldProducer;
        });


    }
/*
    *//**
     * 发送消息带key
     *
     * @param topic
     * @param key
     * @param message
     * @return
     *//*
    public static Future<RecordMetadata> send(String brokers, String topic, String key, String message) {
        KafkaProducer<String, String> producer = getProducer(brokers);
        ProducerRecord<String, String> producerRecord = new ProducerRecord<String, String>(topic, key, message);
        return producer.send(producerRecord, new Callback() {
            @Override
            public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                if ( e != null){
                    log.error("kafka send message error:", e);
                    producer.close();
                    producerCache.remove(brokers);
                }else {
                    log.info("kafka send message success,topic: {},offset:{}",
                            recordMetadata.topic(),
                            recordMetadata.offset());
                }
            }
        });
    }*/


    /**
     * 发送消息
     *
     * @return true=发送成功（leader已确认），false=网络不通/发送失败
     */
    public static boolean send(String brokers, String topic, String key, String message) {
        try {
            KafkaProducer<String, String> producer = getProducer(brokers);
            ProducerRecord<String, String> producerRecord = new ProducerRecord<String, String>(topic, message);
            // 同步等待结果，设置超时时间
            producer.send(producerRecord).get(500, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            // 网络不通、broker不可达、超时，都会走这里
            return false;
        }
    }
/*
    *//**
     * 发送消息，不带参数key的
     *
     * @param topic
     * @param message
     * @return
     *//*
    public static Future<RecordMetadata> send(String brokers, String topic, String message) {
        KafkaProducer<String, String> producer = getProducer(brokers);
        ProducerRecord<String, String> producerRecord = new ProducerRecord<String, String>(topic, message);
        return producer.send(producerRecord);
    }*/

    /** ★★★ 同步发送（最重要） */
    public static void sendSync(String brokers, String topic, String msg) throws Exception {
        KafkaProducer<String, String> producer = getProducer(brokers);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, msg);
        // ★★★ 关键点：get() 会让网络异常立即抛出异常
        producer.send(record).get(1, TimeUnit.SECONDS);
    }

    /**
     * 或者使用 Java 8 的 CompletableFuture 版本（更简洁）
     */
    public static boolean sendBatchAsync(String brokers, String topic, List<String> messages) {
        if (messages.isEmpty()) return false;

        KafkaProducer<String, String> producer = getProducer(brokers);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String msg : messages) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            producer.send(new ProducerRecord<>(topic, msg), (metadata, exception) -> {
                if (exception == null) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(exception);
                }
            });
            futures.add(future);
        }

        try {
            // 等待所有发送完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
            producer.flush();
            log.info("批量发送成功: {} 条", messages.size());
            return true;
        } catch (Exception e) {
            log.error("批量发送失败", e);
            return false;
        }
    }

    /** 同步发送带 key */
    public static void sendSync(String brokers, String topic, String key, String msg) throws Exception {
        KafkaProducer<String, String> producer = getProducer(brokers);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, msg);
        producer.send(record).get(1, TimeUnit.SECONDS);
    }

    /**
     * close method
     *
     * @param key
     * @param v
     */
    private static void accept(String key, KafkaProducer<String, String> v) {
        try {
            v.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
