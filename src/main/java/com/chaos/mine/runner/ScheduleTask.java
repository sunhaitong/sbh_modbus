package com.chaos.mine.runner;

import com.chaos.mine.service.ModbusService;
import com.chaos.mine.service.ModbusTcpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:39
 * @Version 1.0
 */
@Component
@Slf4j
public class ScheduleTask {
    @Autowired
    private ModbusTcpService tcpService;

    @Autowired
    private ModbusService modbusService;

    @Scheduled(fixedRate = 1000)
    @Async// 每2秒读取一次
    public void readModbusData() {
        log.info("readModbusRTUData");
        modbusService.readWeights();
    }


    // Modbus TCP
    @Scheduled(fixedRate = 1000)
    @Async
    public void readTcp() {
        log.info("readTcp");
        tcpService.readTcpData();
    }
}
