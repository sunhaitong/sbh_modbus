package com.chaos.mine.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * 创建producer
     *
     * @param brokers
     * @return
     */
    private static KafkaProducer<String, String> createProducer(String brokers) {
        Properties prop = new Properties();
        prop.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        // prop.put(ProducerConfig.ACKS_CONFIG, "-1");
        prop.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        prop.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // 核心：快速失败
        prop.put(ProducerConfig.RETRIES_CONFIG, 0);
        prop.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 300);
        prop.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 300);
        prop.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 500);
        prop.put(ProducerConfig.ACKS_CONFIG, "1");
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
