package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.runner.SerialConfig;
import com.chaos.mine.runner.SerialPortManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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

/**
 * 压力数据采集服务 (Modbus RTU 协议)
 * 对应 Python 脚本 yali.py
 *
 * 工作状态判断逻辑:
 * - 压力 > 100 MPa 表示进入工作状态
 * - 工作状态下压力持续在120左右波动
 * - 根据压力持续 > 100 的时间判断作业持续时间
 * - 作业持续时间 < 30秒 才发送Kafka数据
 */
@Service
@Slf4j
public class PressureMonitor {

    @Autowired
    private MessageSendService messageSendService;

    @Autowired
    private SerialPortManager serialPortManager;

    @Value("${equip.no:test}")
    private String equipNo;

    // ===================== 可配置参数 =====================
    /** Modbus 从站地址 (设备ID) */
    @Value("${pressure.modbus.slave.id:3}")
    private int slaveId;

    /** 读取间隔 (毫秒) */
    @Value("${pressure.read.interval.ms:1000}")
    private long readIntervalMs;

    /** 工作状态压力阈值 (MPa)，大于此值表示进入工作状态 */
    @Value("${pressure.working.threshold:5}")
    private double workingPressureThreshold;

    /** 最小作业持续时间 (毫秒)，小于此值才发送数据 */
    @Value("${pressure.min.work.duration.ms:30000}")
    private long minWorkDurationMs;

    /** 压力数据接收超时时间 (毫秒) */
    @Value("${pressure.data.receive.timeout.ms:30000}")
    private long dataReceiveTimeoutMs;

    /** 工作状态超时时间 (毫秒)，超过此时间强制结束作业 */
    @Value("${pressure.work.timeout.ms:3600000}")
    private long workTimeoutMs;

    // ===================== Modbus RTU 协议定义 =====================
    /** 功能码: 读保持寄存器 */
    private static final byte FUNC_READ_HOLDING_REGISTERS = 0x03;

    /** 读取的寄存器数量 (2个寄存器组成一个float) */
    private static final int REGISTER_COUNT = 2;

    /** 响应帧最小长度 (地址+功能码+字节数+数据+CRC) */
    private static final int RESPONSE_MIN_LEN = 2 + 1 + 1 + 4 + 2; // = 10

    // ===================== 工作状态机定义 =====================
    private enum WorkState {
        IDLE,       // 空闲状态 (压力 < 阈值)
        WORKING     // 工作中 (压力 >= 阈值)
    }

    private WorkState currentWorkState = WorkState.IDLE;

    // 作业统计相关
    private volatile long workStartTime = 0;        // 本次作业开始时间
    private volatile long workEndTime = 0;          // 本次作业结束时间
    private volatile double maxPressureDuringWork = 0;  // 作业期间最大压力
    private volatile double minPressureDuringWork = Double.MAX_VALUE;  // 作业期间最小压力
    private volatile double avgPressureDuringWork = 0;  // 作业期间平均压力
    private volatile double pressureSumDuringWork = 0;   // 作业期间压力总和
    private volatile int pressureCountDuringWork = 0;    // 作业期间压力采样次数

    // 当前压力值
    private volatile double currentPressure = 0.0;

    // 数据接收监控
    private volatile long lastDataReceiveTime = 0;

    // 统计信息 (所有作业累计)
    private volatile long totalWorkCount = 0;           // 总作业次数
    private volatile long validWorkCount = 0;           // 有效作业次数 (持续时间<30秒)
    private volatile long invalidWorkCount = 0;         // 无效作业次数 (持续时间>=30秒)

    // 串口读取缓冲
    private byte[] buffer = new byte[0];

    // Modbus 请求帧
    private byte[] modbusRequestFrame;

    public PressureMonitor() {

    }

    /**
     * 构建 Modbus 请求帧
     */
    private void buildModbusRequestFrame() {
        byte[] frame = new byte[8];
        frame[0] = (byte) slaveId;
        frame[1] = FUNC_READ_HOLDING_REGISTERS;
        frame[2] = 0x00;
        frame[3] = 0x00;
        frame[4] = 0x00;
        frame[5] = (byte) REGISTER_COUNT;

        int crc = crc16Modbus(Arrays.copyOfRange(frame, 0, 6));
        frame[6] = (byte) (crc & 0xFF);
        frame[7] = (byte) ((crc >> 8) & 0xFF);

        modbusRequestFrame = frame;

        log.info("Modbus请求帧构建完成: {}", bytesToHex(frame));
        log.info("工作压力阈值: {} MPa, 最小作业持续时间: {} 秒",
                workingPressureThreshold, minWorkDurationMs / 1000.0);
    }

    /**
     * 定时读取压力数据
     */
    @Scheduled(fixedDelay = 1000)
    public void scheduledReadPressure() {
        if (!serialPortManager.isOpen()) {
            if (!serialPortManager.ensureSerialPortOpen()) {
                log.warn("串口初始化失败，跳过本次读取");
                return;
            }
        }
        readPressureData();
    }

    /**
     * 超时检查定时任务
     */
    //@Scheduled(fixedDelay = 30000)
    public void checkTimeout() {
        long now = System.currentTimeMillis();

        // 1. 检查数据接收超时
        if (lastDataReceiveTime > 0) {
            long idleTime = now - lastDataReceiveTime;
            if (idleTime > dataReceiveTimeoutMs) {
                log.warn("压力数据接收超时, 已{}秒未收到数据", idleTime / 1000.0);
                // 如果在工作状态，强制结束作业
                if (currentWorkState == WorkState.WORKING) {
                    log.warn("数据接收超时，强制结束当前作业");
                    endWorkSession(false);
                }
                resetBuffer();
            }
        }

        // 2. 检查工作状态超时 (防止异常情况下作业一直不结束)
        if (currentWorkState == WorkState.WORKING && workStartTime > 0) {
            long workDuration = now - workStartTime;
            if (workDuration > workTimeoutMs) {
                log.warn("作业超时 {} 秒，强制结束", workDuration / 1000.0);
                endWorkSession(false);
            }
        }
    }

    /**
     * 每30分钟输出统计信息
     */
    // @Scheduled(fixedDelay = 1800000)
    public void printStatistics() {
        log.info("===== 压力采集统计 =====");
        log.info("设备编号: {}", equipNo);
        log.info("当前状态: {}", currentWorkState);
        log.info("当前压力: {} MPa", String.format("%.2f", currentPressure));
        log.info("工作阈值: {} MPa", workingPressureThreshold);
        log.info("总作业次数: {}", totalWorkCount);
        log.info("有效作业次数(<{}秒): {}", minWorkDurationMs / 1000, validWorkCount);
        log.info("无效作业次数(>={}秒): {}", minWorkDurationMs / 1000, invalidWorkCount);
        if (currentWorkState == WorkState.WORKING && workStartTime > 0) {
            long duration = System.currentTimeMillis() - workStartTime;
            log.info("当前作业已持续: {} 秒", duration / 1000.0);
            log.info("当前作业平均压力: {} MPa",
                    pressureCountDuringWork > 0 ? String.format("%.2f", pressureSumDuringWork / pressureCountDuringWork) : "N/A");
        }
        log.info("最后数据接收时间: {}", lastDataReceiveTime > 0 ? lastDataReceiveTime : "未收到数据");
        log.info("======================");
    }

    /**
     * 读取压力数据
     */
    public void readPressureData() {

        InputStream inputStream = serialPortManager.getInputStream();
        if (inputStream == null) {
            log.error("串口输入流为空");
            return;
        }

        try {
            buildModbusRequestFrame();

            // 发送 Modbus 请求帧
            serialPortManager.sendData(modbusRequestFrame);
            log.info("发送Modbus请求: {}", bytesToHex(modbusRequestFrame));

            Thread.sleep(100);

            // 读取响应数据
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            byte[] tempBuffer = new byte[256];
            int totalBytesRead = 0;
            long startTime = System.currentTimeMillis();
            int timeout = 1000;

            while (System.currentTimeMillis() - startTime < timeout) {
                if (inputStream.available() > 0) {
                    int bytesRead = inputStream.read(tempBuffer);
                    if (bytesRead > 0) {
                        byteOutputStream.write(tempBuffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        if (totalBytesRead >= 9) {
                            break;
                        }
                    }
                }
                Thread.sleep(10);
            }

            byte[] responseData = byteOutputStream.toByteArray();

            if (responseData.length == 0) {
                log.info("未收到响应数据");
                return;
            }

            log.info("收到响应数据，字节数: {}", responseData.length);

            lastDataReceiveTime = System.currentTimeMillis();

            processResponse(responseData);

        } catch (IOException e) {
            log.error("读取压力数据错误: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("线程中断: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理 Modbus 响应数据
     */
    private void processResponse(byte[] responseData) {
        byte[] newBuffer = new byte[buffer.length + responseData.length];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        System.arraycopy(responseData, 0, newBuffer, buffer.length, responseData.length);
        buffer = newBuffer;

        processBuffer();
    }

    /**
     * 处理缓冲区中的数据
     */
    private void processBuffer() {
        while (true) {
            int frameLen = findValidModbusResponse();

            if (frameLen == 0) {
                if (buffer.length >= RESPONSE_MIN_LEN && buffer.length < 100) {
                    log.warn("缓冲区有 {} 字节但找不到有效响应帧，清空缓冲区", buffer.length);
                    buffer = new byte[0];
                }
                break;
            } else {
                log.info("frameLen != 0");
            }

            byte[] frame = Arrays.copyOfRange(buffer, 0, frameLen);
            buffer = Arrays.copyOfRange(buffer, frameLen, buffer.length);

            Double pressure = parsePressureFrame(frame);

            if (pressure != null) {
                processPressureWithWorkState(pressure);
            } else {
                log.info("get pressure is null.");
            }
        }
    }

    /**
     * 查找有效的 Modbus 响应帧
     */
    private int findValidModbusResponse() {
        if (buffer.length < RESPONSE_MIN_LEN) {
            return 0;
        }

        for (int i = 0; i <= buffer.length - RESPONSE_MIN_LEN; i++) {
            if ((buffer[i] & 0xFF) != slaveId) {
                continue;
            }

            if ((buffer[i + 1] & 0xFF) != FUNC_READ_HOLDING_REGISTERS) {
                continue;
            }

            int dataLen = buffer[i + 2] & 0xFF;
            int expectedLen = 3 + dataLen + 2;

            if (i + expectedLen <= buffer.length) {
                byte[] frame = Arrays.copyOfRange(buffer, i, i + expectedLen);
                int crcRecv = (frame[frame.length - 2] & 0xFF) | ((frame[frame.length - 1] & 0xFF) << 8);
                int crcCalc = crc16Modbus(Arrays.copyOfRange(frame, 0, frame.length - 2));

                if (crcRecv == crcCalc) {
                    if (i > 0) {
                        log.warn("跳过 {} 字节脏数据", i);
                    }
                    return expectedLen;
                }
            }
        }

        return 0;
    }

    /**
     * 解析压力数据帧
     */
    private Double parsePressureFrame(byte[] frame) {
        /*if (frame.length < RESPONSE_MIN_LEN) {
            return null;
        }

        if ((frame[0] & 0xFF) != slaveId) {
            return null;
        }

        if ((frame[1] & 0xFF) != FUNC_READ_HOLDING_REGISTERS) {
            return null;
        }

        int dataLen = frame[2] & 0xFF;
        if (dataLen != 4) {
            log.warn("数据长度错误: 期望=4, 实际={}", dataLen);
            return null;
        }

        int crcRecv = (frame[frame.length - 2] & 0xFF) | ((frame[frame.length - 1] & 0xFF) << 8);
        int crcCalc = crc16Modbus(Arrays.copyOfRange(frame, 0, frame.length - 2));

        if (crcRecv != crcCalc) {
            log.debug("CRC校验失败");
            return null;
        }*/

        byte[] floatBytes = Arrays.copyOfRange(frame, 3, 7);
        double pressure = ByteBuffer.wrap(floatBytes).order(ByteOrder.BIG_ENDIAN).getFloat();

        return pressure;
    }

    /**
     * 核心逻辑：根据压力判断工作状态并处理作业
     *
     * 逻辑说明：
     * 1. 压力 > 阈值(100MPa) 表示进入工作状态
     * 2. 工作状态下记录开始时间，累积压力数据
     * 3. 压力 <= 阈值 表示退出工作状态，结束作业
     * 4. 作业结束时判断持续时间：
     *    - 持续时间 < 30秒: 发送Kafka数据
     *    - 持续时间 >= 30秒: 不发送数据
     */
    private void processPressureWithWorkState(double pressure) {
        long currentTime = System.currentTimeMillis();
        currentPressure = pressure;

        log.info("当前压力: {} MPa, 工作阈值: {} MPa, 当前状态: {}",
                String.format("%.2f", pressure), workingPressureThreshold, currentWorkState);

        // 判断是否进入工作状态
        boolean isWorking = pressure >= workingPressureThreshold;

        if (isWorking) {
            try {
                List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
                long sampleTime = workEndTime;

                // 1. 主要作业数据
                DeviceDataVO workData = new DeviceDataVO();
                workData.setEquipNum(equipNo);
                workData.setPointNum("pressure_work");
                workData.setParamNum("pressure");
                workData.setValue(pressure);  // 作业持续时间
                workData.setSampleTime(sampleTime);
                workData.setRecvTime(currentTime);        // recvTime 存储持续时间
                deviceDataVOS.add(workData);

                log.info("✅ 已发送作业数据到Kafka - 压力: {} MPa",
                        pressure);
                // 发送到Kafka
                messageSendService.batchSendMsg2Kafka("pressure_work", deviceDataVOS);



            } catch (Exception e) {
                log.error("发送作业数据到Kafka失败: {}", e.getMessage(), e);
            }
        } else {
            log.info("not working ");
        }
        /*switch (currentWorkState) {
            case IDLE:
                if (isWorking) {
                    // 进入工作状态，开始新的作业
                    startWorkSession(currentTime, pressure);
                } else {
                    // 空闲状态，不做处理
                    log.debug("空闲状态，当前压力: {} MPa", String.format("%.2f", pressure));
                }
                break;

            case WORKING:
                if (isWorking) {
                    // 工作中，更新作业统计数据
                    updateWorkSession(pressure);
                } else {
                    // 退出工作状态，结束作业
                    endWorkSession(true);
                }
                break;
        }*/
    }

    /**
     * 开始新的作业会话
     */
    private void startWorkSession(long startTime, double initialPressure) {
        currentWorkState = WorkState.WORKING;
        workStartTime = startTime;
        workEndTime = 0;
        maxPressureDuringWork = initialPressure;
        minPressureDuringWork = initialPressure;
        pressureSumDuringWork = initialPressure;
        pressureCountDuringWork = 1;

        log.info("========== 作业开始 ==========");
        log.info("开始时间: {}", workStartTime);
        log.info("起始压力: {} MPa", String.format("%.2f", initialPressure));
        log.info("压力阈值: {} MPa", workingPressureThreshold);
        log.info("==============================");
    }

    /**
     * 更新作业会话数据
     */
    private void updateWorkSession(double pressure) {
        // 更新最大压力
        if (pressure > maxPressureDuringWork) {
            maxPressureDuringWork = pressure;
            log.debug("作业期间最大压力更新: {} MPa", String.format("%.2f", pressure));
        }

        // 更新最小压力
        if (pressure < minPressureDuringWork) {
            minPressureDuringWork = pressure;
            log.debug("作业期间最小压力更新: {} MPa", String.format("%.2f", pressure));
        }

        // 累加压力值用于计算平均值
        pressureSumDuringWork += pressure;
        pressureCountDuringWork++;

        // 计算平均压力
        avgPressureDuringWork = pressureSumDuringWork / pressureCountDuringWork;

        long currentDuration = System.currentTimeMillis() - workStartTime;
        log.debug("作业进行中 - 当前压力: {} MPa, 已持续: {} 秒, 平均压力: {} MPa",
                String.format("%.2f", pressure),
                currentDuration / 1000.0,
                String.format("%.2f", avgPressureDuringWork));
    }

    /**
     * 结束作业会话
     * @param shouldEvaluate 是否需要评估并发送数据 (true: 正常结束需要评估, false: 超时强制结束)
     */
    private void endWorkSession(boolean shouldEvaluate) {
        workEndTime = System.currentTimeMillis();
        long workDuration = workEndTime - workStartTime;

        // 计算平均压力
        if (pressureCountDuringWork > 0) {
            avgPressureDuringWork = pressureSumDuringWork / pressureCountDuringWork;
        }

        log.info("========== 作业结束 ==========");
        log.info("结束时间: {}", workEndTime);
        log.info("作业持续时间: {} 秒 ({} 毫秒)", workDuration / 1000.0, workDuration);
        log.info("最大压力: {} MPa", String.format("%.2f", maxPressureDuringWork));
        log.info("最小压力: {} MPa", String.format("%.2f", minPressureDuringWork));
        log.info("平均压力: {} MPa", String.format("%.2f", avgPressureDuringWork));
        log.info("采样次数: {}", pressureCountDuringWork);
        log.info("结束原因: {}", shouldEvaluate ? "压力低于阈值" : "超时强制结束");

        // 更新总作业次数
        totalWorkCount++;

        // 核心判断逻辑：持续时间小于30秒才发送数据
        if (shouldEvaluate && workDuration < minWorkDurationMs) {
            // 有效作业：持续时间小于30秒，发送Kafka数据
            validWorkCount++;
            log.info(">>> 作业有效: 持续时间 {} 秒 < {} 秒，准备发送Kafka数据",
                    workDuration / 1000.0, minWorkDurationMs / 1000.0);

            // 发送Kafka数据
            sendWorkDataToKafka(workDuration);

        } else if (shouldEvaluate) {
            // 无效作业：持续时间大于等于30秒，不发送数据
            invalidWorkCount++;
            log.info(">>> 作业无效: 持续时间 {} 秒 >= {} 秒，不发送Kafka数据",
                    workDuration / 1000.0, minWorkDurationMs / 1000.0);

            // 可选：记录无效作业日志供分析
            logInvalidWork(workDuration);

        } else {
            // 超时强制结束，不发送数据
            log.warn(">>> 作业因超时强制结束，不发送Kafka数据");
        }

        log.info("==============================");

        // 重置工作状态
        resetWorkState();
    }

    /**
     * 发送作业数据到Kafka
     * @param workDuration 作业持续时间(毫秒)
     */
    private void sendWorkDataToKafka(long workDuration) {
        if (!DataConfigManager.getInstance().isSampleFlag()) {
            log.info("采样标志为false，不发送数据");
            return;
        }

        try {
            List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
            long sampleTime = workEndTime;

            // 1. 主要作业数据
            DeviceDataVO workData = new DeviceDataVO();
            workData.setEquipNum(equipNo);
            workData.setPointNum("pressure_work");
            workData.setParamNum("maogan");
            workData.setValue((double) workDuration);  // 作业持续时间
            workData.setSampleTime(sampleTime);
            workData.setRecvTime(workDuration);        // recvTime 存储持续时间
            deviceDataVOS.add(workData);


            // 发送到Kafka
            messageSendService.batchSendMsg2Kafka("pressure_work", deviceDataVOS);

            log.info("✅ 已发送作业数据到Kafka - 持续时间: {}秒, 最大压力: {} MPa, 平均压力: {} MPa",
                    workDuration / 1000.0,
                    String.format("%.2f", maxPressureDuringWork),
                    String.format("%.2f", avgPressureDuringWork));

        } catch (Exception e) {
            log.error("发送作业数据到Kafka失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 记录无效作业信息（用于分析）
     */
    private void logInvalidWork(long workDuration) {
        // 可以写入专门的日志文件或数据库
        log.info("[INVALID_WORK] duration={}ms, maxPressure={}MPa, avgPressure={}MPa, samples={}",
                workDuration,
                String.format("%.2f", maxPressureDuringWork),
                String.format("%.2f", avgPressureDuringWork),
                pressureCountDuringWork);
    }

    /**
     * 重置工作状态变量
     */
    private void resetWorkState() {
        currentWorkState = WorkState.IDLE;
        workStartTime = 0;
        workEndTime = 0;
        maxPressureDuringWork = 0;
        minPressureDuringWork = Double.MAX_VALUE;
        avgPressureDuringWork = 0;
        pressureSumDuringWork = 0;
        pressureCountDuringWork = 0;
    }

    /**
     * 重置缓冲区
     */
    private void resetBuffer() {
        buffer = new byte[0];
    }

    /**
     * CRC16 Modbus校验
     */
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

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 100); i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        if (bytes.length > 100) {
            sb.append("...");
        }
        return sb.toString().trim();
    }

    /**
     * 获取当前压力值
     */
    public Double getCurrentPressure() {
        return currentPressure;
    }

    /**
     * 获取当前工作状态
     */
    public String getCurrentWorkState() {
        return currentWorkState.name();
    }

    /**
     * 获取当前作业已持续时间（毫秒）
     */
    public long getCurrentWorkDuration() {
        if (currentWorkState == WorkState.WORKING && workStartTime > 0) {
            return System.currentTimeMillis() - workStartTime;
        }
        return 0;
    }

    /**
     * 获取总作业次数
     */
    public long getTotalWorkCount() {
        return totalWorkCount;
    }

    /**
     * 获取有效作业次数
     */
    public long getValidWorkCount() {
        return validWorkCount;
    }

    /**
     * 获取无效作业次数
     */
    public long getInvalidWorkCount() {
        return invalidWorkCount;
    }

    /**
     * 手动重置所有统计
     */
    public void manualReset() {
        log.warn("手动重置压力监控所有统计");
        resetWorkState();
        resetBuffer();
        totalWorkCount = 0;
        validWorkCount = 0;
        invalidWorkCount = 0;
        currentPressure = 0;
        lastDataReceiveTime = 0;
    }

    @PreDestroy
    public void cleanup() {
        // 如果还在工作状态，结束当前作业
        if (currentWorkState == WorkState.WORKING) {
            log.info("服务关闭，结束当前作业");
            endWorkSession(false);
        }
        buffer = new byte[0];
        log.info("===== PressureMonitor 资源清理完成 =====");
        log.info("最终统计 - 总作业: {}, 有效作业(<{}秒): {}, 无效作业(>={}秒): {}",
                totalWorkCount,
                minWorkDurationMs / 1000,
                validWorkCount,
                minWorkDurationMs / 1000,
                invalidWorkCount);
    }
}