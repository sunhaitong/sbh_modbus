package com.chaos.modbus.sbh.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * kafka工具类
 *
 * @author sunht
 * @date 2021/12/15
 */
@Component
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

    /**
     * 发送消息带key
     *
     * @param topic
     * @param key
     * @param message
     * @return
     */
    public static Future<RecordMetadata> send(String brokers, String topic, String key, String message) {
        KafkaProducer<String, String> producer = getProducer(brokers);
        ProducerRecord<String, String> producerRecord = new ProducerRecord<String, String>(topic, key, message);
        return producer.send(producerRecord);
    }


    /**
     * 发送消息，不带参数key的
     *
     * @param topic
     * @param message
     * @return
     */
    public static Future<RecordMetadata> send(String brokers, String topic, String message) {
        KafkaProducer<String, String> producer = getProducer(brokers);
        ProducerRecord<String, String> producerRecord = new ProducerRecord<String, String>(topic, message);
        return producer.send(producerRecord);
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
