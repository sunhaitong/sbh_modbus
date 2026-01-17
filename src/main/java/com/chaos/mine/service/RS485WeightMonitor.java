package com.chaos.mine.service;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

@Service
@Slf4j
public class RS485WeightMonitor {

    @Autowired
    private MessageSendService messageSendService;

    // ===================== 串口对象 =====================
    private final SerialPort serialPort;
    private InputStream inputStream;
    private byte[] buffer = new byte[0];
    private int readCount = 0;

    // ===================== 构造函数注入 =====================
    public RS485WeightMonitor(SerialPort serialPort) {
        this.serialPort = serialPort;
        initializeInputStream();
    }

    // ===================== 初始化输入流 =====================
    private void initializeInputStream() {
        try {
            if (serialPort != null && serialPort.isOpen()) {
                // 设置读取超时
                serialPort.setComPortTimeouts(
                        SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                        100,  // 读取超时100ms，避免阻塞太久
                        0     // 写入超时0秒
                );

                inputStream = serialPort.getInputStream();
                log.info("串口输入流初始化成功: " + serialPort.getSystemPortName());
            } else {
                log.error("串口未打开或为空");
            }
        } catch (Exception e) {
            log.error("初始化输入流错误: " + e.getMessage());
            e.printStackTrace();
            cleanup();
        }
    }

    public void readSerialData() {
        readCount++;
        log.info("=== 第 " + readCount + " 次读取 ===");

        if (inputStream == null) {
            log.error("串口输入流未初始化");
            // 尝试重新初始化
            initializeInputStream();
            return;
        }

        try {
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
                log.info("收到原始 HEX=" + bytesToHex(receivedData));

                // 合并缓冲区
                byte[] newBuffer = new byte[buffer.length + receivedData.length];
                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                System.arraycopy(receivedData, 0, newBuffer, buffer.length, receivedData.length);
                buffer = newBuffer;

                // 处理完整的帧
                int frameCount = 0;
                while (buffer.length >= 15) {
                    byte[] frame = Arrays.copyOfRange(buffer, 0, 15);
                    buffer = Arrays.copyOfRange(buffer, 15, buffer.length);
                    parseFrame(frame);
                    frameCount++;
                }

                if (frameCount > 0) {
                    log.info("成功处理 " + frameCount + " 个完整帧");
                }

                // 如果缓冲区还有剩余数据但不够一帧
                if (buffer.length > 0 && buffer.length < 15) {
                    log.info("缓冲区有 " + buffer.length + " 字节，等待下一帧数据");
                }
            } else {
                log.info("本次读取无数据");
            }

        } catch (IOException e) {
            log.error("读取串口数据错误: " + e.getMessage());
            // 尝试重新初始化输入流
            initializeInputStream();
        } catch (Exception e) {
            log.error("数据处理错误: " + e.getMessage());
            e.printStackTrace();
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
        log.info("完整帧 HEX=" + bytesToHex(frame));

        if (frame.length != 15) {
            log.warn("帧长度异常 len=" + frame.length);
            return;
        }

        byte[] expectedHeader = hexStringToByteArray("02 10 00 00 00 03 06");
        byte[] actualHeader = Arrays.copyOfRange(frame, 0, 7);

        if (!Arrays.equals(expectedHeader, actualHeader)) {
            log.warn("帧头不匹配");
            return;
        }

        int crcRecv = (frame[13] & 0xFF) | ((frame[14] & 0xFF) << 8);
        int crcCalc = crc16Modbus(Arrays.copyOfRange(frame, 0, 13));

        log.info(String.format("CRC recv=0x%04X calc=0x%04X", crcRecv, crcCalc));

        if (crcRecv != crcCalc) {
            log.error("CRC 校验失败");
            return;
        }

        // ===== 关键修正点 =====
        // 有符号 32 位整数（补码）
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOfRange(frame, 7, 11));
        buffer.order(ByteOrder.BIG_ENDIAN);
        int weightKg = buffer.getInt();

        double weightT = Math.round(weightKg / 1000.0 * 1000.0) / 1000.0;
        messageSendService.sendMsg2Kafka("kaugnche", "kaungche_weight", weightT);
        
        log.info(String.format("解析结果：%d kg  |  %.3f t", weightKg, weightT));
    }

    // ===================== 清理资源 =====================
    public void cleanup() {
        log.info("正在清理资源...");

        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                log.error("关闭输入流错误: " + e.getMessage());
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

        log.info("资源清理完成");
    }

    // ===================== 工具函数 =====================
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private byte[] hexStringToByteArray(String hexString) {
        String[] hexValues = hexString.split(" ");
        byte[] bytes = new byte[hexValues.length];
        for (int i = 0; i < hexValues.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hexValues[i], 16);
        }
        return bytes;
    }
}