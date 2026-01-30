package com.chaos.mine.service;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class DistanceSensorService {
/*
    *//* ================= 基础参数 ================= *//*
    private static final String PORT = "COM3";
    private static final int SLAVE_ID = 2;

    private static final int ENCODER_RESOLUTION = 1024;
    private static final double WHEEL_CIRCUM_MM = 100.0;

    *//* ================= 算法参数 ================= *//*
    private static final double ZERO_THRESHOLD_M = 0.005;     // 自动归零阈值
    private static final double NOISE_THRESHOLD_M = 0.0005;  // 抖动死区
    private static final double MIN_VALID_STROKE_M = 0.02;   // 最小有效拉伸

    *//* ================= 状态变量 ================= *//*
    private final AtomicLong base = new AtomicLong(0);

    private volatile ModbusMaster master;

    private double lastDistance = 0;
    private double lastDelta = 0;
    private double maxCandidate = 0;

    *//* ================= 定时采样（20Hz） ================= *//*
    @Scheduled(fixedRate = 50)
    public void sample() {
        try {
            ensureConnected();

            int high = readHolding(0);
            int low = readHolding(1);

            long encoder = ((long) high << 16) | (low & 0xFFFF);

            double distanceM = calcDistance(encoder);

            // 自动归零
            if (Math.abs(distanceM) < ZERO_THRESHOLD_M) {
                base.set(encoder);
                resetState();
                return;
            }

            processSample(distanceM);

        } catch (Exception e) {
            log.error("采样异常，释放串口，等待下次恢复", e);
            destroyMaster();
        }
    }

    *//* ================= 核心算法 ================= *//*
    private void processSample(double distance) {
        double delta = distance - lastDistance;

        // 抖动死区
        if (Math.abs(delta) < NOISE_THRESHOLD_M) {
            delta = 0;
        }

        // 拉伸阶段：记录最大值
        if (delta > 0) {
            maxCandidate = Math.max(maxCandidate, distance);
        }

        // 方向反转：拉伸 → 回缩
        if (lastDelta > 0 && delta <= 0) {
            if (maxCandidate >= MIN_VALID_STROKE_M) {
                onRealMeasurement(maxCandidate);
            }
            maxCandidate = 0;
        }

        lastDelta = delta;
        lastDistance = distance;
    }

    *//* ================= 真实测量值回调 ================= *//*
    private void onRealMeasurement(double value) {
        log.info("🎯 真实测量值 = {} m", String.format("%.4f", value));

        // 👉 这里你可以：
        // 1. 存数据库
        // 2. 发 Kafka
        // 3. 推 WebSocket
    }

    *//* ================= 位移计算 ================= *//*
    private double calcDistance(long encoder) {
        double mm =
                (encoder - base.get()) * WHEEL_CIRCUM_MM / ENCODER_RESOLUTION;
        return mm / 1000.0;
    }

    *//* ================= Modbus 读取 ================= *//*
    private int readHolding(int offset) throws Exception {
        BaseLocator<Number> locator =
                BaseLocator.holdingRegister(SLAVE_ID, offset, 0);
        return master.getValue(locator).intValue();
    }

    *//* ================= 串口懒加载 ================= *//*
    private synchronized void ensureConnected() throws ModbusInitException {
        if (master != null) {
            return;
        }

        log.info("初始化 Modbus RTU 串口...");

        SerialPortWrapper wrapper = new SerialPortWrapper() {
            @Override public void open() {
                master.init();
            }

            @Override
            public InputStream getInputStream() {
                return null;
            }

            @Override
            public OutputStream getOutputStream() {
                return null;
            }

            @Override public void close() {}
            @Override public String getPortName() { return PORT; }
            @Override public int getBaudRate() { return 9600; }
            @Override public int getDataBits() { return 8; }
            @Override public int getStopBits() { return 1; }
            @Override public int getParity() { return 0; }
            @Override public int getFlowControlIn() { return 0; }
            @Override public int getFlowControlOut() { return 0; }
        };

        ModbusFactory factory = new ModbusFactory();
        master = factory.createRtuMaster(wrapper);
        master.setTimeout(500);
        master.setRetries(1);
        master.init();

        log.info("Modbus RTU 串口已连接");
    }

    *//* ================= 释放串口 ================= *//*
    private synchronized void destroyMaster() {
        if (master != null) {
            try {
                master.destroy();
            } catch (Exception ignore) {}
            master = null;
        }
    }

    private void resetState() {
        lastDistance = 0;
        lastDelta = 0;
        maxCandidate = 0;
    }

    @PreDestroy
    public void shutdown() {
        destroyMaster();
        log.info("服务关闭，串口释放");
    }*/
}
