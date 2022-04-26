package com.chaos.modbus.sbh.runner;

import com.alibaba.fastjson.JSON;
import com.chaos.modbus.sbh.entity.DeviceInfo;
import com.chaos.modbus.sbh.entity.ReadOffset;
import com.chaos.modbus.sbh.entity.SignalData;
import com.chaos.modbus.sbh.entity.SingleFeatureData;
import com.chaos.modbus.sbh.util.KafkaUtils;
import com.chaos.modbus.sbh.util.ModbusUtils;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author sunht
 * @date 2022/1/25
 */
@Component
@Slf4j
public class ModbusRunner {
    private int count = 100;
    // 每天5分钟提取一次
    @Scheduled(cron = "0 1/5 * * * ?")
    public void temperatureHandle() throws ErrorResponseException, ModbusTransportException, ModbusInitException {
        // 转发温度传感器数据
        log.info("start to send temp data.....");
        String tempHost = "192.169.1.222";
        Map<Integer, DeviceInfo> tempDevInfoMap = DataConfigManager.getInstance().getTemperatureDeviceInfoMap();
        int temTotal= tempDevInfoMap.size();
        List<ReadOffset> tempOffsets = getReadOffset(temTotal);
        log.info("start to send temp data. tempOffsets size {}", tempOffsets.size());
        for (ReadOffset offset : tempOffsets) {
            doSend(tempHost, 1, offset.getStart(), offset.getEnd(), DataType.TWO_BYTE_INT_SIGNED, tempDevInfoMap);
        }
        log.info("end to send temp data.....");

    }

    // 每天5分钟提取一次
    @Scheduled(cron = "0 1/5 * * * ?")
    public void hydrogenHandle() throws ErrorResponseException, ModbusTransportException, ModbusInitException {
        // 处理氢气
        String hydrogenHost = "192.169.1.224";
        log.info("start to send hydrogen data.....");
        Map<Integer, DeviceInfo> hydrogenDevInfoMap = DataConfigManager.getInstance().getHydrogenDeviceInfoMap();
        // 处理两个变压器数据
        for (Map.Entry<Integer, DeviceInfo> entry : hydrogenDevInfoMap.entrySet()) {
            doSendHydrogen(hydrogenHost, 1, entry.getKey(), entry.getValue());
            doSendHydrogen(hydrogenHost, 2, entry.getKey(), entry.getValue());
        }
        log.info("end to send hydrogen data.....");
    }

    // 每天5分钟提取一次 遥测
    @Scheduled(cron = "0 1/5 * * * ?")
    public void measureHandle() throws ErrorResponseException, ModbusTransportException, ModbusInitException {
        // 处理遥测
        String host = "192.169.1.223";
        log.info("start to send measure data.....");

        Map<Integer, DeviceInfo> measureDevInfoMap = DataConfigManager.getInstance().getMeasureDeviceInfoMap();
        List<ReadOffset> measureOffsets = getReadOffset(measureDevInfoMap.size());
        for (ReadOffset offset : measureOffsets) {
            doSend(host, 1, offset.getStart(), offset.getEnd(), DataType.FOUR_BYTE_FLOAT, measureDevInfoMap);
        }
        log.info("end to send measure data.....");
        log.info("start to send signal data.....");
        Map<Integer, DeviceInfo> signalDevInfoMap = DataConfigManager.getInstance().getSignalDeviceInfoMap();
        for (Map.Entry<Integer, DeviceInfo> entry : signalDevInfoMap.entrySet()) {
            doSendCoilStatus(host, 1, entry.getKey(), entry.getValue());
        }

        log.info("end to send signal data.....");
    }

    private void doSend(String host, int slaveId, int start, int end, int dataType, Map<Integer, DeviceInfo> deviceInfoMap) throws ErrorResponseException, ModbusTransportException, ModbusInitException {
        BatchResults<Integer> result = ModbusUtils.batchReadInput(host, slaveId, start, end, dataType);
        if (deviceInfoMap.isEmpty()) {
            log.info("device info map is null.");
            return;
        }

        for (int i = start; i <= end; i++) {
            Object valueObj = result.getValue(i);
            if (valueObj != null) {
                try {
                    Double value = Double.valueOf(valueObj.toString());
                    DeviceInfo deviceInfo = deviceInfoMap.get(i + 1);
                    if (deviceInfo != null && StringUtils.isNotBlank(deviceInfo.getDevId())) {
                        SingleFeatureData data = new SingleFeatureData();

                        if (host.equals("192.169.1.222")) {
                            data.setValue(value * 0.1);
                        } else if (host.equals("192.169.1.223")) {
                            if (deviceInfo.getRatio() != null && deviceInfo.getRatio() != 0.0D) {
                                data.setValue(value * deviceInfo.getRatio());
                            } else {
                                data.setValue(value);
                            }
                            /*if (deviceInfo.getKpIid().endsWith("电压") || convertList.contains(deviceInfo.getKpIid())) {
                                data.setValue(value * 0.001);
                            } else if (deviceInfo.getKpIid().equals("频率")) {
                                data.setValue(value * 0.014652);
                            } else {
                                data.setValue(value);
                            }*/
                        } else {
                            data.setValue(value);
                        }
                        data.setKpiId(deviceInfo.getKpIid());
                        data.setDevId(deviceInfo.getDevId());
                        data.setPointId(deviceInfo.getPointNo());
                        // 发送数据到kafka
                        String kafkaUrl = DataConfigManager.getInstance().getKafkaUrl();
                        String topic = DataConfigManager.getInstance().getKpiTopic();
                        KafkaUtils.send(kafkaUrl, topic, JSON.toJSONString(data));
                    } else {
                        log.info("get device info is null. index:{}", i+1);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } else {
                log.info("get valueObj is null.");
            }
        }
    }

    /**
     * 读取开关量
     * @param host
     * @param slaveId
     * @param deviceInfo
     * @throws ErrorResponseException
     * @throws ModbusTransportException
     * @throws ModbusInitException
     */
    private void doSendCoilStatus(String host, int slaveId, int offset, DeviceInfo deviceInfo) throws ErrorResponseException, ModbusTransportException, ModbusInitException {
        Boolean res = ModbusUtils.readCoilStatus(slaveId, offset, host);
        if (null == deviceInfo) {
            log.info("device info map is null.");
            return;
        }
        if (StringUtils.isNotBlank(deviceInfo.getDevId())) {
            SignalData data = new SignalData();
            data.setValue(res ? 1 : 0);
            data.setKpiId(deviceInfo.getKpIid());
            data.setDevId(deviceInfo.getDevId());
            data.setPointId(deviceInfo.getPointNo());
            // 发送数据到kafka
            String kafkaUrl = DataConfigManager.getInstance().getKafkaUrl();
            String topic = DataConfigManager.getInstance().getKpiTopic();
            KafkaUtils.send(kafkaUrl, topic, JSON.toJSONString(data));
        } else {
            log.info("device id is null.");
        }


    }


    /**
     * 读取氢气
     * @param host
     * @param slaveId
     * @param deviceInfo
     * @throws ErrorResponseException
     * @throws ModbusTransportException
     * @throws ModbusInitException
     */
    private void doSendHydrogen(String host, int slaveId, int offset, DeviceInfo deviceInfo) throws ErrorResponseException, ModbusTransportException, ModbusInitException {
        Number res = ModbusUtils.readHoldingRegister(host, slaveId, offset, DataType.TWO_BYTE_INT_SIGNED);
        if (null == deviceInfo) {
            log.info("device info map is null.");
            return;
        }

        SingleFeatureData data = new SingleFeatureData();
        data.setValue(Double.valueOf(res.toString()));
        data.setKpiId(deviceInfo.getKpIid());
        if (slaveId == 1) {
            data.setDevId("824192E01");
        } else {
            data.setDevId("824193E01");

        }
        data.setPointId("02");
        // 发送数据到kafka
        String kafkaUrl = DataConfigManager.getInstance().getKafkaUrl();
        String topic = DataConfigManager.getInstance().getKpiTopic();
        KafkaUtils.send(kafkaUrl, topic, JSON.toJSONString(data));
    }



    /**
     * 获取读取寄存器list
     * @param total
     * @return
     */
    private List<ReadOffset> getReadOffset(int total) {
        List<ReadOffset> offsets = new ArrayList<>();
        for (int i = 0; i <= total; i++) {
            if (i % count == 0 && (total - i) > count) {
                offsets.add(new ReadOffset(i, i + 99));
            }
        }
        if (total % count != 0) {
            offsets.add(new ReadOffset((total / count) * 100, (total / count) * 100 + total % 100));
        }
        return offsets;
    }
}
