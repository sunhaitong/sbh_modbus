package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.CRC16Util;
import com.chaos.mine.util.MineCartWeighTool;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:36
 * @Version 1.0
 */
@Slf4j
@Service
public class ModbusService {

    @Autowired
    private MessageSendService messageSendService;

    @Value("${equip.no:test}")
    private String equipNo;

    @Value("${scale.serial.portName:COM1}")
    private String portName;

    private SerialPort serialPort;
    private final AtomicReference<Double> singleWeight = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalWeight = new AtomicReference<>(0.0);

    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong  atomicLong = new AtomicLong(0L);

    /**
     * 打开串口
     */
    private synchronized void openSerialPort() {
        if (serialPort != null && serialPort.isOpen()) {
            return;
        }

        if (serialPort == null) {
            serialPort = SerialPort.getCommPort(portName);
            serialPort.setBaudRate(9600);
            serialPort.setNumDataBits(8);
            serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
            serialPort.setParity(SerialPort.NO_PARITY);
        }

        if (!serialPort.isOpen()) {
            if (serialPort.openPort()) {
                log.info("串口 {} 已打开", portName);
            } else {
                log.error("无法打开串口: {}", portName);
                throw new RuntimeException("串口打开失败: " + portName);
            }
        }
    }

    /**
     * 关闭串口
     */
    public synchronized void closeSerialPort() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            log.info("串口 {} 已关闭", portName);
        }
    }

    /**
     * 检查串口是否可用，不可用时重新打开
     */
    private void ensureSerialPortOpen() {
        if (serialPort == null || !serialPort.isOpen()) {
            log.warn("串口未打开，尝试重新打开...");
            openSerialPort();
        }
    }

    private byte[] readModbusResponseDynamic(InputStream in) throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];

        long startTime = System.currentTimeMillis();
        int timeout = 1000;
        int frameTimeout = 50; // 帧间超时

        long lastDataTime = System.currentTimeMillis();
        boolean readingFrame = true;

        while (readingFrame && System.currentTimeMillis() - startTime < timeout) {
            if (in.available() > 0) {
                int read = in.read(buffer);
                if (read > 0) {
                    response.write(buffer, 0, read);
                    lastDataTime = System.currentTimeMillis();

                    // 检查是否已收到完整帧
                    byte[] currentData = response.toByteArray();
                    if (isModbusFrameComplete(currentData)) {
                        readingFrame = false;
                    }
                }
            } else {
                // 检查帧间超时
                if (System.currentTimeMillis() - lastDataTime > frameTimeout) {
                    if (response.size() > 0) {
                        readingFrame = false; // 认为帧接收完成
                    }
                }
            }
            Thread.sleep(2);
        }

        if (response.size() == 0) {
            throw new TimeoutException("Modbus响应超时");
        }

        byte[] fullResponse = response.toByteArray();

        // 验证CRC
        if (!verifyCRC(fullResponse)) {
            throw new Exception("CRC校验失败");
        }

        return fullResponse;
    }

    private boolean isModbusFrameComplete(byte[] data) {
        if (data.length < 2) return false;

        byte functionCode = data[1];

        switch (functionCode) {
            case 0x03: // 读寄存器响应
                if (data.length < 3) return false;
                byte byteCount = data[2];
                return data.length >= (3 + byteCount + 2); // 头3字节 + 数据 + CRC2

            case 0x06: // 写寄存器响应
                return data.length >= 8; // 固定8字节

            default:
                return data.length >= 4; // 最小帧长
        }
    }

    private boolean verifyCRC(byte[] data) {
        if (data.length < 2) return false;

        int crc = 0xFFFF;
        for (int i = 0; i < data.length - 2; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc = crc >> 1;
                }
            }
        }

        byte crcLow = (byte) (crc & 0xFF);
        byte crcHigh = (byte) ((crc >> 8) & 0xFF);

        return (data[data.length - 2] == crcLow && data[data.length - 1] == crcHigh);
    }

    public synchronized void readWeights() {
        try {
            // 确保串口已打开
            ensureSerialPortOpen();

            // 读单铲重量 0x01 0x03 0x00 0x00 0x00 0x02 + CRC
            byte[] cmd1 = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x02};
            byte[] crc1 = CRC16Util.getCRC(cmd1);
            byte[] request1 = ByteBuffer.allocate(cmd1.length + 2).put(cmd1).put(crc1).array();
            byte[] resp1 = sendAndReceive(request1);
            List<DeviceDataVO> deviceDataVOS = new ArrayList<>();

            boolean sendFlag = false;
            if (resp1 != null && resp1.length >= 9) {
                int val = ((resp1[3] & 0xFF) << 24) | ((resp1[4] & 0xFF) << 16)
                        | ((resp1[5] & 0xFF) << 8) | (resp1[6] & 0xFF);
                if ((double) val / 1000 < 2) {
                    log.info("weight < 2 send:{}", val);
                    if (atomicBoolean.get()) {
                        log.info("Single weight: {}", (double) val / 1000);
                        // messageSendService.sendMsg2Kafka("02","singleWeight", (double) val / 1000);
                        DeviceDataVO single = new DeviceDataVO();
                        single.setEquipNum(equipNo);
                        single.setPointNum("02");
                        single.setParamNum("singleWeight");
                        single.setValue(MineCartWeighTool.calculateRealWeight(equipNo));
                        single.setSampleTime(System.currentTimeMillis());
                        single.setRecvTime(System.currentTimeMillis());
                        deviceDataVOS.add(single);
                        sendFlag = true;
                    } else {
                        log.info("kong zai....");
                    }
                } else {
                    log.info("current weight: {}", (double) val / 1000);
                    MineCartWeighTool.processWeight(equipNo, (double) val / 1000);
                    atomicBoolean.set(true);
                    atomicLong.set(System.currentTimeMillis());
                }
                singleWeight.set((double) val / 1000); // 单位是kg

            }



            // 读累计重量 0x01 0x03 0x00 0x02 0x00 0x02 + CRC
            byte[] cmd2 = new byte[]{0x01, 0x03, 0x00, 0x02, 0x00, 0x02};
            byte[] crc2 = CRC16Util.getCRC(cmd2);
            byte[] request2 = ByteBuffer.allocate(cmd2.length + 2).put(cmd2).put(crc2).array();
            byte[] resp2 = sendAndReceive(request2);
            if (resp2 != null && resp2.length >= 9) {
                int val = ((resp2[3] & 0xFF) << 24) | ((resp2[4] & 0xFF) << 16)
                        | ((resp2[5] & 0xFF) << 8) | (resp2[6] & 0xFF);
                totalWeight.set((double) val / 1000);
                log.info("Total weight: {}", (double) val / 1000 );
                // messageSendService.sendMsg2Kafka("02","totalWeight", (double) val / 1000);
                DeviceDataVO totalWeight = new DeviceDataVO();
                totalWeight.setEquipNum(equipNo);
                totalWeight.setPointNum("02");
                totalWeight.setParamNum("totalWeight");
                totalWeight.setValue((double) val / 1000);
                totalWeight.setSampleTime(System.currentTimeMillis());
                totalWeight.setRecvTime(atomicLong.get());
                deviceDataVOS.add(totalWeight);
            }


            if (sendFlag && DataConfigManager.getInstance().isSampleFlag()) {
                messageSendService.batchSendMsg2Kafka("weight", deviceDataVOS);
                atomicBoolean.set(false);
                atomicLong.set(0L);
            }

        } catch (Exception e) {
            log.error("读取重量失败", e);
            // 如果发生异常，关闭串口以便下次重连
            closeSerialPort();
        }
    }

    private byte[] sendAndReceive(byte[] request) throws Exception {
        ensureSerialPortOpen();

        try (OutputStream out = serialPort.getOutputStream();
             InputStream in = serialPort.getInputStream()) {

            // 清空输入缓冲区
            while (in.available() > 0) {
                in.read();
            }

            // 发送请求
            out.write(request);
            out.flush();

            return readModbusResponseDynamic(in);
        }
    }


    public double getSingleWeight() {
        return singleWeight.get();
    }

    public double getTotalWeight() {
        return totalWeight.get();
    }
}