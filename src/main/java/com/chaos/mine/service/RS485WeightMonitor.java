package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.MineCartWeighTool;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@EnableScheduling
public class RS485WeightMonitor {

    @Autowired
    private MessageSendService messageSendService;

    private final AtomicReference<Double> singleWeight = new AtomicReference<>(0.0);
    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong atomicLong = new AtomicLong(0L);

    @Value("${equip.no:test}")
    private String equipNo;

    @Value("${scale.serial.portName:COM1}")
    private String portName;

    // ===================== 串口对象 =====================
    private SerialPort serialPort;
    private InputStream inputStream;
    private byte[] buffer = new byte[0];
    private int readCount = 0;
    private boolean initialized = false;

    // ===================== 帧头定义 =====================
    private static final byte[] EXPECTED_HEADER = new byte[] {
            (byte) 0x02, (byte) 0x10, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x03, (byte) 0x06
    };

    // ===================== 构造函数 =====================
    public RS485WeightMonitor() {
        // 构造函数中不再注入SerialPort
    }

    // ===================== 初始化串口 =====================
    private synchronized boolean initializeSerialPort() {
        if (initialized) {
            return true;
        }

        try {
            log.info("正在初始化串口: {}", portName);
            serialPort = SerialPort.getCommPort(portName);

            if (serialPort == null) {
                log.error("串口 {} 不存在", portName);
                return false;
            }

            // 如果串口已打开，先关闭
            if (serialPort.isOpen()) {
                serialPort.closePort();
            }

            // 打开串口
            boolean opened = serialPort.openPort();
            if (!opened) {
                log.error("无法打开串口: " + portName);
                return false;
            }
            log.info("串口已打开: {}", portName);

            // 设置串口参数
            serialPort.setComPortParameters(9600, 8, 1, SerialPort.NO_PARITY);

            // 设置读取超时
            serialPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    100,  // 读取超时100ms
                    0     // 写入超时0秒
            );

            inputStream = serialPort.getInputStream();
            initialized = true;
            log.info("串口初始化成功: {}", portName);

        } catch (Exception e) {
            log.error("初始化串口错误: {}", e.getMessage(), e);
            cleanup();
        }
        return initialized;
    }

    // ===================== 每秒读取一次 =====================
    //@Scheduled(fixedRate = 1000)
    public void scheduledRead() {
        if (!initialized) {
            initializeSerialPort();
            if (!initialized) {
                log.warn("串口初始化失败，跳过本次读取");
                return;
            }
        }

        readSerialData();
    }

    // ===================== 读取串口数据 =====================
    public void readSerialData() {
        readCount++;
        log.debug("=== 第 {} 次读取 ===", readCount);

        if (inputStream == null || !initialized) {
            log.error("串口未初始化");
            initializeSerialPort();
            return;
        }

        try {
            // 检查串口是否仍然打开
            if (!serialPort.isOpen()) {
                log.error("串口已关闭，重新初始化");
                initialized = false;
                initializeSerialPort();
                return;
            }

            // 读取串口数据
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            byte[] tempBuffer = new byte[1024];
            int totalBytesRead = 0;

            // 读取所有可用数据
            while (inputStream.available() > 0) {
                int bytesRead = inputStream.read(tempBuffer);
                if (bytesRead > 0) {
                    byteOutputStream.write(tempBuffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    // 防止读取过多数据
                    if (totalBytesRead > 1024) {
                        log.warn("读取数据超过1KB，可能数据异常");
                        break;
                    }
                }
            }

            byte[] receivedData = byteOutputStream.toByteArray();

            if (receivedData.length > 0) {
                log.info("收到原始数据，字节数: {}", receivedData.length);
                log.info("HEX: {}", bytesToHex(receivedData));

            /*    // 合并缓冲区
                byte[] newBuffer = new byte[buffer.length + receivedData.length];
                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                System.arraycopy(receivedData, 0, newBuffer, buffer.length, receivedData.length);*/
                buffer = receivedData;

                // 处理完整的帧
                int frameCount = 0;
                while (buffer.length >= 15) {
                    byte[] frame = Arrays.copyOfRange(buffer, 0, 15);
                    buffer = Arrays.copyOfRange(buffer, 15, buffer.length);
                    parseFrame(frame);
                    frameCount++;
                }

                if (frameCount > 0) {
                    log.info("成功处理 {} 个完整帧", frameCount);
                }

                // 如果缓冲区还有剩余数据但不够一帧
                if (buffer.length > 0 && buffer.length < 15) {
                    log.debug("缓冲区有 {} 字节，等待下一帧数据", buffer.length);
                }
            } else {
                log.debug("本次读取无数据");
            }

        } catch (IOException e) {
            log.error("读取串口数据错误: {}", e.getMessage(), e);
            initialized = false;
            initializeSerialPort();  // 重新初始化
        } catch (Exception e) {
            log.error("数据处理错误: {}", e.getMessage(), e);
        }
    }

    // ===================== CRC16 Modbus =====================
    private int crc16Modbus(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc;
    }

    // ===================== 帧解析 =====================
    private void parseFrame(byte[] frame) {
        if (frame.length != 15) {
            log.warn("帧长度异常 len={}", frame.length);
            return;
        }

        // 检查帧头
        boolean headerMatch = true;
        for (int i = 0; i < EXPECTED_HEADER.length; i++) {
            if (frame[i] != EXPECTED_HEADER[i]) {
                log.warn("帧头第 {} 位不匹配: 期望={} 实际={}",
                        i,
                        String.format("%02X", EXPECTED_HEADER[i]),
                        String.format("%02X", frame[i]));
                headerMatch = false;
                break;
            }
        }

        if (!headerMatch) {
            log.warn("帧头不匹配");
            return;
        }

        // CRC校验
        int crcRecv = (frame[13] & 0xFF) | ((frame[14] & 0xFF) << 8);
        int crcCalc = crc16Modbus(Arrays.copyOfRange(frame, 0, 13));

        if (crcRecv != crcCalc) {
            log.error("CRC 校验失败: recv=0x{} calc=0x{}",
                    String.format("%04X", crcRecv),
                    String.format("%04X", crcCalc));
            return;
        }

        // 解析重量数据 (字节 7-10)
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOfRange(frame, 7, 11));
        buffer.order(ByteOrder.BIG_ENDIAN);
        int weightKg = buffer.getInt();

        double weightT = Math.round(weightKg / 1000.0 * 1000.0) / 1000.0;

        log.info("解析到重量: {} kg ({} t)", weightKg, weightT);

        List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
        boolean sendFlag = false;

        if (weightT < 2) {
            log.info("weight < 2 send: {}", weightT);
            if (atomicBoolean.get()) {
                log.info("Single weight: {}", weightT);
                DeviceDataVO kaugnche = new DeviceDataVO();
                kaugnche.setEquipNum(equipNo);
                kaugnche.setPointNum("kaugnche");
                kaugnche.setParamNum("kaungche_weight");
                kaugnche.setValue(MineCartWeighTool.calculateRealWeight(equipNo));
                kaugnche.setSampleTime(System.currentTimeMillis());
                kaugnche.setRecvTime(atomicLong.get());
                deviceDataVOS.add(kaugnche);
                sendFlag = true;
            } else {
                log.info("kong zai....");
            }
        } else {
            log.info("current weight: {}", weightT);
            MineCartWeighTool.processWeight(equipNo, weightT);
            atomicBoolean.set(true);
            atomicLong.set(System.currentTimeMillis());
        }
        singleWeight.set(weightT); // 单位是kg

        log.debug("sendFlag: {} sampleFlag: {}, atomicBoolean：{}",
                sendFlag,
                DataConfigManager.getInstance().isSampleFlag(),
                atomicBoolean.get());

        if (sendFlag && DataConfigManager.getInstance().isSampleFlag()) {
            messageSendService.batchSendMsg2Kafka("kaugnche", deviceDataVOS);
            atomicBoolean.set(false);
            atomicLong.set(0L);
        }

        log.info("解析结果：{} kg | {} t", weightKg, weightT);
    }

    // ===================== 获取当前重量 =====================
    public Double getCurrentWeight() {
        return singleWeight.get();
    }

    // ===================== 清理资源 =====================
    @PreDestroy
    public void cleanup() {
        log.info("正在清理串口资源...");

        if (inputStream != null) {
            try {
                inputStream.close();
                log.info("输入流已关闭");
            } catch (IOException e) {
                log.error("关闭输入流错误: {}", e.getMessage(), e);
            }
        }

        if (serialPort != null && serialPort.isOpen()) {
            boolean isClosed = serialPort.closePort();
            if (isClosed) {
                log.info("串口已关闭");
            } else {
                log.error("关闭串口失败");
            }
        }

        initialized = false;
        buffer = new byte[0];
        log.info("串口资源清理完成");
    }

    // ===================== 手动关闭串口 =====================
    public void closeSerialPort() {
        cleanup();
    }

    // ===================== 手动打开串口 =====================
    public boolean openSerialPort() {
        if (initialized && serialPort != null && serialPort.isOpen()) {
            log.info("串口已经打开");
            return true;
        }

        initialized = false;
        return initializeSerialPort();
    }

    // ===================== 工具函数 =====================
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}