package com.chaos.mine.service;

import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class EncoderReadTask {
    @Value("${equip.no:test}")
    private String equipNo;
    @Autowired
    private MessageSendService messageSendService;

    private static final int ENCODER_RESOLUTION = 1024;
    private static final double WHEEL_CIRCUM_MM = 100.0;
    private static final double ZERO_THRESHOLD_M = 0.005;

    // 自动归零阈值
    private static final double NOISE_THRESHOLD_M = 0.0005;  // 抖动死区
    private static final double MIN_VALID_STROKE_M = 0.02;   // 最小有效拉伸

    private double lastDistance = 0;
    private double lastDelta = 0;
    private double maxCandidate = 0;

    /* ================= 状态变量 ================= */
    private final AtomicLong base = new AtomicLong(0);


    @Autowired
    private LaShengService modbusService;


    public void readEncoder() {
        try {
            short [] regs = modbusService.readHoldingRegisters(0x0000, 2);

            int high = regs[0] & 0xFFFF;
            int low = regs[1] & 0xFFFF;

            long encoderValue = ((long) high << 16) | low;

            double distanceMm =
                    (encoderValue - base.get()) * WHEEL_CIRCUM_MM / ENCODER_RESOLUTION;
            double distanceM = distanceMm / 1000.0;

            // 自动归零
            if (Math.abs(distanceM) < ZERO_THRESHOLD_M) {
                base.set(encoderValue);
                resetState();
                return;
            }

            processSample(distanceM);
        } catch (Exception e) {
            System.err.println("Modbus 读取失败：" + e.getMessage());
        }
    }

    private void resetState() {
        lastDistance = 0;
        lastDelta = 0;
        maxCandidate = 0;
    }


    /* ================= 核心算法 ================= */
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


    /* ================= 真实测量值回调 ================= */
    private void onRealMeasurement(double value) {
        log.info("真实测量值 = {} m", String.format("%.4f", value));
        List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
        DeviceDataVO distance = new DeviceDataVO();
        distance.setEquipNum(equipNo);
        distance.setPointNum("01");
        distance.setParamNum("distance");
        distance.setValue(value);
        distance.setSampleTime(System.currentTimeMillis());
        distance.setRecvTime(System.currentTimeMillis());
        deviceDataVOS.add(distance);

        log.info("distance kafka data:{}", JSON.toJSONString(deviceDataVOS));
        if (DataConfigManager.getInstance().isSampleFlag()) {
            log.info("send distance to kafka");
            messageSendService.batchSendMsg2Kafka("distance", deviceDataVOS);
        } else {
            log.info("speed < 200  send rfid to kafka pass.");
        }
    }

}
