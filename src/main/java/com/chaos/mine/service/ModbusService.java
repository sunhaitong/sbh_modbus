package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.runner.SerialConfig;
import com.chaos.mine.runner.SerialPortManager;
import com.chaos.mine.util.CRC16Util;
import com.chaos.mine.util.MineCartWeighTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ModbusService {

    @Autowired
    private MessageSendService messageSendService;

    @Autowired
    private SerialPortManager serialPortManager;

    @Autowired
    private SerialConfig serialConfig;

    @Value("${equip.no:test}")
    private String equipNo;

    private final AtomicReference<Double> singleWeight = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalWeight = new AtomicReference<>(0.0);
    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong atomicLong = new AtomicLong(0L);

    public synchronized void readWeights() {
        InputStream in = null;
        OutputStream out = null;
        try {
            // 确保串口可用
            if (!serialPortManager.ensureSerialPortOpen()) {
                log.error("串口不可用");
                return;
            }

            // 获取输出流和输入流
            out = serialPortManager.getOutputStream();
            in = serialPortManager.getInputStream();

            // 读单铲重量 0x01 0x03 0x00 0x00 0x00 0x02
            byte[] cmd1 = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x02};
            byte[] crc1 = CRC16Util.getCRC(cmd1);
            byte[] request1 = ByteBuffer.allocate(cmd1.length + 2)
                    .put(cmd1).put(crc1).array();

            byte[] resp1 = sendAndReceive(request1, out, in);
            List<DeviceDataVO> deviceDataVOS = new ArrayList<>();

            boolean sendFlag = false;
            if (resp1 != null && resp1.length >= 9) {
                int val = ((resp1[3] & 0xFF) << 24) | ((resp1[4] & 0xFF) << 16)
                        | ((resp1[5] & 0xFF) << 8) | (resp1[6] & 0xFF);
                double weight = (double) val / 1000;

                if (weight < 2) {
                    log.info("weight < 2 send:{}", weight);
                    if (atomicBoolean.get()) {
                        log.info("Single weight: {}", weight);
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
                    log.info("current weight: {}", weight);
                    MineCartWeighTool.processWeight(equipNo, weight);
                    atomicBoolean.set(true);
                    atomicLong.set(System.currentTimeMillis());
                }
                singleWeight.set(weight);
            }

            // 读累计重量 0x01 0x03 0x00 0x02 0x00 0x02
            byte[] cmd2 = new byte[]{0x01, 0x03, 0x00, 0x02, 0x00, 0x02};
            byte[] crc2 = CRC16Util.getCRC(cmd2);
            byte[] request2 = ByteBuffer.allocate(cmd2.length + 2)
                    .put(cmd2).put(crc2).array();

            byte[] resp2 = sendAndReceive(request2, out, in);
            if (resp2 != null && resp2.length >= 9) {
                int val = ((resp2[3] & 0xFF) << 24) | ((resp2[4] & 0xFF) << 16)
                        | ((resp2[5] & 0xFF) << 8) | (resp2[6] & 0xFF);
                double weight = (double) val / 1000;
                totalWeight.set(weight);
                log.info("Total weight: {}", weight);

                DeviceDataVO totalWeightVO = new DeviceDataVO();
                totalWeightVO.setEquipNum(equipNo);
                totalWeightVO.setPointNum("02");
                totalWeightVO.setParamNum("totalWeight");
                totalWeightVO.setValue(weight);
                totalWeightVO.setSampleTime(System.currentTimeMillis());
                totalWeightVO.setRecvTime(atomicLong.get());
                deviceDataVOS.add(totalWeightVO);
            }

            if (sendFlag && DataConfigManager.getInstance().isSampleFlag()) {
                messageSendService.batchSendMsg2Kafka("weight", deviceDataVOS);
                atomicBoolean.set(false);
                atomicLong.set(0L);
            }

        } catch (Exception e) {
            log.error("读取重量失败", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private byte[] sendAndReceive(byte[] request, OutputStream out, InputStream in) throws Exception {
        // 清空输入缓冲区
        serialPortManager.clearInputStream();

        // 发送请求
        out.write(request);
        out.flush();

        return readModbusResponseDynamic(in);
    }

    public double getSingleWeight() {
        return singleWeight.get();
    }

    public double getTotalWeight() {
        return totalWeight.get();
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
}