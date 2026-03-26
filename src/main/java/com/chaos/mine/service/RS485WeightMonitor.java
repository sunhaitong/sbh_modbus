package com.chaos.mine.service;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.runner.SerialConfig;
import com.chaos.mine.runner.SerialPortManager;
import com.chaos.mine.util.MineCartWeighTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
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

    @Autowired
    private SerialPortManager serialPortManager;

    @Autowired
    private SerialConfig serialConfig;

    private final AtomicReference<Double> singleWeight = new AtomicReference<>(0.0);
    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong atomicLong = new AtomicLong(0L);

    @Value("${equip.no:test}")
    private String equipNo;

    // ===================== 帧头定义 =====================
    private static final byte[] EXPECTED_HEADER = new byte[] {
            (byte) 0x02, (byte) 0x10, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x03, (byte) 0x06
    };

    private byte[] buffer = new byte[0];
    private int readCount = 0;

    //@Scheduled(fixedRate = 1000)
    public void scheduledRead() {
        if (!serialPortManager.isOpen()) {
            if (!serialPortManager.ensureSerialPortOpen()) {
                log.warn("串口初始化失败，跳过本次读取");
                return;
            }
        }
        readSerialData();
    }

    public void readSerialData() {
        readCount++;
        log.debug("=== 第 {} 次读取 ===", readCount);

        InputStream inputStream = serialPortManager.getInputStream();
        if (inputStream == null) {
            log.error("串口输入流为空");
            return;
        }

        try {
            // 读取串口数据
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            byte[] tempBuffer = new byte[1024];
            int totalBytesRead = 0;

            while (inputStream.available() > 0) {
                int bytesRead = inputStream.read(tempBuffer);
                if (bytesRead > 0) {
                    byteOutputStream.write(tempBuffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    if (totalBytesRead > 1024) {
                        log.warn("读取数据超过1KB，可能数据异常");
                        break;
                    }
                }
            }

            byte[] receivedData = byteOutputStream.toByteArray();

            if (receivedData.length > 0) {
                log.info("收到原始数据，字节数: {}", receivedData.length);

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

                if (buffer.length > 0 && buffer.length < 15) {
                    log.debug("缓冲区有 {} 字节，等待下一帧数据", buffer.length);
                }
            } else {
                log.debug("本次读取无数据");
            }

        } catch (IOException e) {
            log.error("读取串口数据错误: {}", e.getMessage(), e);
        }
    }

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
        singleWeight.set(weightT);

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

    public Double getCurrentWeight() {
        return singleWeight.get();
    }

    @PreDestroy
    public void cleanup() {
        // 不再直接关闭串口，由SerialPortManager统一管理
        buffer = new byte[0];
        log.info("RS485WeightMonitor资源清理完成");
    }
}