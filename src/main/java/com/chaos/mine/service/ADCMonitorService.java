package com.chaos.mine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

@Service
@Slf4j
public class ADCMonitorService {

    private static final String ADC_NODE = "/sys/devices/platform/fec10000.saradc/iio:device0/";
    private static final double VOLTAGE_SCALE = 15.0 / 4096.0;  // 电压换算系数

    @Autowired
    private MessageSendService messageSendService;

    /**
     * 每秒执行一次的定时任务
     */
    /*@Scheduled(fixedRate = 10000)
    @Async*/// 1000毫秒 = 1秒
    public void monitorADC() {
        try {
            // 读取ADC原始值
            int rawValue2 = readRawADCValue("in_voltage2_raw");
            int rawValue4 = readRawADCValue("in_voltage4_raw");
            int rawValue6 = readRawADCValue("in_voltage6_raw");

            // 计算电压值
            double voltage2 = VOLTAGE_SCALE * rawValue2;
            messageSendService.sendMsg2Kafka("switch", "feishi", voltage2);

            double voltage4 = VOLTAGE_SCALE * rawValue4;
            messageSendService.sendMsg2Kafka("switch", "kuangshi", voltage4);

            double voltage6 = VOLTAGE_SCALE * rawValue6;
            messageSendService.sendMsg2Kafka("switch", "other", voltage6);

        } catch (IOException e) {
            log.error("读取ADC节点失败: {}", e.getMessage());
        }
    }

    /**
     * 从ADC节点读取原始值
     */
    private int readRawADCValue(String type) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(ADC_NODE + type))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return Integer.parseInt(line.trim());
            } else {
                throw new IOException("ADC节点返回空值");
            }
        } catch (NumberFormatException e) {
            throw new IOException("ADC节点返回无效的数字格式: " + e.getMessage());
        }
    }
}