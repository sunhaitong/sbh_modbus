package com.chaos.mine.service;

import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/21 18:23
 * @Version 1.0
 */
@Slf4j
@Service
public class MessageSendService {
    @Value("${equip.no:test}")
    private String equipNo;

    @Value("${point.no:test}")
    private String pointNo;

    @Value("${kafka.url}")
    private String kafkaHost;

    @Value("${kafka.topic}")
    private String kafkaTopic;

    public void sendMsg2Kafka(String paramCode, Double value) {
        try {
            DeviceDataVO deviceDataVO = new DeviceDataVO();
            deviceDataVO.setEquipNum(equipNo);
            deviceDataVO.setPointNum(pointNo);
            deviceDataVO.setParamNum(paramCode);
            deviceDataVO.setValue(value);
            deviceDataVO.setSampleTime(System.currentTimeMillis());
            deviceDataVO.setRecvTime(System.currentTimeMillis());
            log.info("send msg to kafka data:{}", JSON.toJSONString(deviceDataVO));
            KafkaUtils.send(kafkaHost, kafkaTopic, JSON.toJSONString(deviceDataVO));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
}
