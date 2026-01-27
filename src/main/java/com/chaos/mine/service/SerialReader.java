package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SerialReader {

    @Value("${rfid.serial.port.name:COM4}")
    private String portName;
    private static final int BAUD_RATE = 115200;
    private SerialPort comPort;

    // 使用线程安全的队列
    private BlockingQueue<Byte> byteQueue = new ArrayBlockingQueue<>(1024);

    @Autowired
    private MessageSendService messageSendService;

    private ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

    @Value("${equip.no:test}")
    private String equipNo;

    /**
     * 初始化串口（应用启动时执行）
     */
    @PostConstruct
    public void init() {
        try {
            log.info("开始初始化串口...");
            // 打开指定串口
            comPort = SerialPort.getCommPort(portName);
            comPort.setBaudRate(BAUD_RATE);

            // 设置串口参数
            comPort.setNumDataBits(8);
            comPort.setNumStopBits(1);
            comPort.setParity(SerialPort.NO_PARITY);
            comPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);

            // 设置超时
            comPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    1000,
                    0
            );

            // 打开串口
            if (comPort.openPort()) {
                log.info("串口 " + portName + " 打开成功（保持常开）");
            } else {
                log.info("无法打开串口: " + portName);
                comPort = null;
            }

        } catch (Exception e) {
            log.error("串口初始化失败:{} ", e.getMessage());
            comPort = null;
        }
    }

    /**
     * 定时读取串口数据（每3秒执行一次）
     */
    /*@Scheduled(fixedDelay = 1000)
    @Async*/
    public void readSerialData() {
        cache.clear();
        if (comPort == null || !comPort.isOpen()) {
            log.error("串口未打开，跳过本次读取");
            return;
        }

        try {
            // 检查是否有数据可读
            int bytesAvailable = comPort.bytesAvailable();
            if (bytesAvailable > 0) {
               /* // 读取所有可用数据到临时缓冲区
                byte[] tempBuffer = new byte[bytesAvailable];
                int bytesRead = comPort.readBytes(tempBuffer, bytesAvailable);

                if (bytesRead > 0) {
                    // 将新读取的数据添加到缓冲区
                    System.arraycopy(tempBuffer, 0, buffer, bufferIndex, bytesRead);
                    bufferIndex += bytesRead;

                    // 处理缓冲区中的数据（每次2个字节）
                    processBufferData();
                } else {
                    log.info("无数据可读");
                }*/
                byte[] buffer = new byte[bytesAvailable];
                int bytesRead = comPort.readBytes(buffer, bytesAvailable);

                // 将读取的字节放入队列
                for (int i = 0; i < bytesRead; i++) {
                    byteQueue.offer(buffer[i]);
                }
                // 处理队列中的数据（每次2字节）
                processQueueData();
            } else {
                log.info("无可用数据，等待下一周期");
            }
        } catch (Exception e) {
            log.error("读取串口数据时发生错误:{} ", e.getMessage());
        }
    }

    private void processQueueData() {
        // 当队列中有至少2个字节时处理
        while (byteQueue.size() >= 2) {
            try {
                byte[] twoBytes = new byte[2];
                twoBytes[0] = byteQueue.take();  // 第一个字节
                twoBytes[1] = byteQueue.take();  // 第二个字节

                // 调试输出
                log.info("从队列读取: %02X %02X {}, {}",
                        twoBytes[0] & 0xFF, twoBytes[1] & 0xFF);

                processTwoBytes(twoBytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 处理两个字节的数据
     */
    private void processTwoBytes(byte[] data) {
        if (data.length != 2) {
            log.warn("数据长度不是2个字节");
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        log.info("原始数据(HEX): ");
        for (int i = 0; i < data.length; i++) {
            log.info("%02X {}", data[i] & 0xFF);
        }

        // 解析为16位整数
        int value = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        // messageSendService.sendMsg2Kafka("01", "rfid", (double)value);
        List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
        DeviceDataVO rfid = new DeviceDataVO();
        rfid.setEquipNum(equipNo);
        rfid.setPointNum("01");
        rfid.setParamNum("rfid");
        rfid.setValue((double)value);
        rfid.setSampleTime(System.currentTimeMillis());
        rfid.setRecvTime(System.currentTimeMillis());
        deviceDataVOS.add(rfid);

        if (DataConfigManager.getInstance().isSampleFlag()) {
            String key = equipNo + "01" + value;
            if (cache.get(key) != null) {
                messageSendService.batchSendMsg2Kafka("rfid", deviceDataVOS);
            }
        }
    }

    /**
     * 关闭串口（应用关闭时执行）
     */
    @PreDestroy
    public void close() {
        if (comPort != null && comPort.isOpen()) {
            comPort.closePort();
            log.info("串口已关闭");
        }
    }

}