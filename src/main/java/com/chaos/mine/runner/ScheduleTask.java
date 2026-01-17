package com.chaos.mine.runner;

import com.chaos.mine.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private SerialReader serialReader;

    @Autowired
    private ADCMonitorService adcMonitorService;

    @Autowired
    private TboxDataService tboxDataService;

    @Autowired
    private HeartService heartService;

    @Autowired
    private RS485WeightMonitor rs485WeightMonitor;

    @Value("${sampling.kuangche.flag:1}")
    private Integer kaungcheFlag;

    @Scheduled(fixedDelay = 1000)
    @Async
    public void sample(){
        new Thread(() -> {
            log.info("readSerialData");
            serialReader.readSerialData();

            log.info("monitorADC");
            adcMonitorService.monitorADC();

            log.info("readTcpData");
            tcpService.readTcpData();

            log.info("readSerialDataPeriodically");
            tboxDataService.readSerialDataPeriodically();

            log.info("heartBeat");
            heartService.heartBeat();

            if (kaungcheFlag == 1) {
                log.info("领拓 readWeights");
                modbusService.readWeights();
            } else if (kaungcheFlag == 2) {
                log.info("GHH矿卡 rs485WeightMonitor");
                rs485WeightMonitor.readSerialData();
            }else if (kaungcheFlag == 3) {
                log.info("安百拓矿卡 readWeights");
            }

        }).start();
    }

    /*@Scheduled(fixedRate = 1000)
    @Async*/// 每2秒读取一次
    public void readModbusData() {
        log.info("readModbusRTUData");
        modbusService.readWeights();
    }


    // Modbus TCP
    /*@Scheduled(fixedRate = 1000)
    @Async*/
    public void readTcp() {
        log.info("readTcp");
        tcpService.readTcpData();
    }
}
