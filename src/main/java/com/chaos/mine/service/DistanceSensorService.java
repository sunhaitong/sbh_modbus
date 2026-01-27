package com.chaos.mine.service;

import com.fazecast.jSerialComm.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 测距
 *
 */
//@Service
public class DistanceSensorService {

    private static final String PORT_NAME = "COM3";
    private static final int SLAVE_ID = 2;
    private static final int ENCODER_RESOLUTION = 1024;
    private static final double WHEEL_CIRCUM_MM = 100.0;
    private static final double ZERO_THRESHOLD_M = 0.005;

    private SerialPort serialPort;
    private int base = 0;
    private double maxDistance = 0.0;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @PostConstruct
    public void init() {
        serialPort = SerialPort.getCommPort(PORT_NAME);
        serialPort.setBaudRate(9600);
        serialPort.setNumDataBits(8);
        serialPort.setNumStopBits(1);
        serialPort.setParity(SerialPort.NO_PARITY);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        if (!serialPort.openPort()) {
            System.err.println("串口连接失败");
            return;
        }
        System.out.println("开始持续读取位移（单位：米，带自动归零），Ctrl+C 退出\n");
    }

    public void readDistance() {
        if (serialPort == null || !serialPort.isOpen()) return;

        byte[] buffer = new byte[4]; // 2个寄存器，每个2字节
        int bytesRead = serialPort.readBytes(buffer, buffer.length);

        if (bytesRead != 4) {
            System.err.println("读取失败，字节数不足");
            return;
        }

        // Modbus RTU 寄存器数据，假设高位在前
        int high = ((buffer[0] & 0xFF) << 8) | (buffer[1] & 0xFF);
        int low  = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        int encoderValue = (high << 16) | low;

        // 位移计算
        double distanceMm = (encoderValue - base) * WHEEL_CIRCUM_MM / ENCODER_RESOLUTION;
        double distanceM = distanceMm / 1000.0;

        // 自动归零逻辑
        String state;
        if (Math.abs(distanceM) < ZERO_THRESHOLD_M) {
            base = encoderValue;
            distanceM = 0.0;
            state = "零位(自动归零)";
        } else {
            state = distanceM > 0 ? "拉出" : "回缩";
        }

        // 更新最大距离
        if (distanceM > maxDistance) {
            maxDistance = distanceM;
            sendToKafka(maxDistance);
        }

        System.out.printf(
                "寄存器HEX: [0x%04X, 0x%04X] | 编码器值: %6d | 位移: %8.4f m | 状态: %s | 最长距离: %8.4f m%n",
                high, low, encoderValue, distanceM, state, maxDistance
        );
    }

    private void sendToKafka(double distance) {
        String message = String.format("最长距离更新: %.4f 米", distance);
        kafkaTemplate.send("distance-topic", message);
        System.out.println("已发送到 Kafka: " + message);
    }

    @PreDestroy
    public void cleanup() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            System.out.println("串口已关闭");
        }
    }
}