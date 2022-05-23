package com.chaos.modbus.sbh.runner;

import com.alibaba.fastjson.JSON;
import com.chaos.modbus.sbh.entity.*;
import com.chaos.modbus.sbh.util.KafkaUtils;
import com.chaos.modbus.sbh.util.ModbusUtils;
import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.BaseLocator;
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
    @Scheduled(cron = "0 1/40 * * * ?")
    public void getData() {
       ModbusUtils.scheduleTest();
    }

    public static void main(String[] args) {
        ModbusUtils.scheduleTest();
    }
}
