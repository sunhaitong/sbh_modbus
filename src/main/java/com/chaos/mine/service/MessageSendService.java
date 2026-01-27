package com.chaos.mine.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.offline.DataService;
import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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

    @Autowired
    private DataService dataService;

    /*public void sendMsg2Kafka(String pointNO, String paramCode, Double value) {
        try {
            DeviceDataVO deviceDataVO = new DeviceDataVO();
            deviceDataVO.setEquipNum(equipNo);
            deviceDataVO.setPointNum(pointNO);
            deviceDataVO.setParamNum(paramCode);
            deviceDataVO.setValue(value);
            deviceDataVO.setSampleTime(System.currentTimeMillis());
            deviceDataVO.setRecvTime(System.currentTimeMillis());
            log.info("send msg to kafka data:{}", JSON.toJSONString(deviceDataVO));
            dataService.sendMsg2Kafka(deviceDataVO);
           // KafkaUtils.send(kafkaHost, kafkaTopic, JSON.toJSONString(deviceDataVO));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }*/

    public void batchSendMsg2Kafka(String dataType, List<DeviceDataVO> deviceDataVOList) {
        try {
            if (CollectionUtils.isNotEmpty(deviceDataVOList)) {
                JSONObject json = new JSONObject();
                json.put("dataType", dataType);
                json.put("deviceDataVOList", JSON.toJSONString(deviceDataVOList));
                dataService.batchSendMsg2Kafka(true,kafkaTopic, equipNo, json.toJSONString());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
}
