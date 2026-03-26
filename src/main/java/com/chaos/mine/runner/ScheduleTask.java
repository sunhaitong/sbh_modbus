package com.chaos.mine.runner;

import cn.hutool.core.date.StopWatch;
import com.chaos.mine.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
    private ADCMonitorService adcMonitorService;

    @Autowired
    private TboxDataService tboxDataService;

    @Autowired
    private HeartService heartService;

    @Autowired
    private CanWeightReader canWeightReader;

    @Autowired
    private RfidReaderService rfidReaderService;


    @Autowired
    private RS485WeightMonitor rs485WeightMonitor;

/*    @Autowired
    private DistanceSensorService distanceSensorService;*/

    @Value("${sampling.kuangche.flag:1}")
    private Integer kaungcheFlag;

    @Value("${switch.flag:1}")
    private Integer switchFlag;

    @Value("${rfid.flag:1}")
    private Integer rfidFlag;

    @Value("${distance.flag:1}")
    private Integer distanceFlag;

    @Autowired
    private EncoderReadTask encoderReadTask;


    @Scheduled(fixedDelay = 1000)
    @Async
    public void sample(){
        //log.info("readRfidData");

        //serialReader.readSerialData();

        log.info("readTcpData");
        tcpService.readTcpData();

        log.info("readSerialDataPeriodically");
        tboxDataService.readSerialDataPeriodically();

        if (distanceFlag == 1) {
            log.info("distance read.");
            encoderReadTask.readEncoder();
        } else {
            log.info("distance read.... off");
        }

        if (kaungcheFlag == 1) {
            log.info("领拓 readWeights");
            modbusService.readWeights();
        } else if (kaungcheFlag == 2) {
            log.info("GHH矿卡 rs485WeightMonitor");
            rs485WeightMonitor.scheduledRead();
        }else if (kaungcheFlag == 3) {
            log.info("安百拓矿卡 readWeights");
            canWeightReader.getLatestWeightTons();

        }
/*

            log.info("distance read.");
            distanceSensorService.readDistance();
*/
    }

    @Scheduled(fixedDelay = 180000)
    @Async
    public void  readSwitch() {
        log.info("monitorADC");
        if (switchFlag == 1) {
            adcMonitorService.monitorADC();
        } else {
            adcMonitorService.readSwitch2();
        }
    }

    @Scheduled(fixedRate = 30000)
    @Async
    public void heartBeat() {
        log.info("heartBeat");
        heartService.heartBeat();
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

    @Scheduled(fixedRate = 1000)
    @Async
    public void readRfid() {
        if (rfidFlag == 1) {
            log.info("rfid read.");
            rfidReaderService.readRfidTask();
        } else {
            log.info("rfid read..... off");
        }
    }
}
