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
import java.util.concurrent.atomic.AtomicLong;

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

    @Autowired
    private TboxDataService tboxDataService;

    @Value("${equip.no:test}")
    private String equipNo;

    // 在类中添加一个标志位，记录是否已经执行过累清操作
    private volatile boolean hasPerformedClearTotal = false;

    // ===================== 可配置参数 =====================
    /** 重量阈值 (吨)，默认2吨 */
    @Value("${weight.threshold.t:2.0}")
    private double weightThreshold;

    /** 状态超时时间 (毫秒)，默认30分钟 */
    @Value("${weight.state.timeout.ms:1800000}")
    private long stateTimeoutMs;

    /** 超时检查间隔 (毫秒)，默认2分钟 */
    @Value("${weight.timeout.check.interval.ms:120000}")
    private long timeoutCheckIntervalMs;

    /** 数据接收超时时间 (毫秒)，默认10分钟 */
    @Value("${weight.data.receive.timeout.ms:600000}")
    private long dataReceiveTimeoutMs;

    // ===================== 帧头定义 =====================
    private static final byte[] EXPECTED_HEADER = new byte[] {
            (byte) 0x02, (byte) 0x10, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x03, (byte) 0x06
    };
    private static final int FRAME_LEN = 15;

    // ===================== 状态机定义 =====================
    private enum State {
        EMPTY,      // 空车状态 (重量 < 阈值)
        LOADING,    // 装车中 (重量 >= 阈值，正在增加)
        UNLOADING   // 卸车中 (刚从高位下降到阈值以下)
    }

    private State currentState = State.EMPTY;

    // 时间统计相关
    private volatile long lastUnloadTime = 0;           // 上次卸车完成时间
    private volatile long currentLoadStartTime = 0;     // 本次装车开始时间（作业开始时间）
    private volatile long currentPeakTime = 0;          // 本次峰值时间
    private volatile double currentPeakWeight = 0;      // 本次峰值重量
    private volatile double lastFuel = 0;               // 上次油耗

    // 数据接收监控
    private volatile long lastDataReceiveTime = 0;      // 最后收到数据的时间

    // 串口读取缓冲 - 改为追加模式
    private byte[] buffer = new byte[0];

    // 统计信息
    private final AtomicLong totalUnloadCount = new AtomicLong(0);
    private volatile double totalMaterialWeight = 0;

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

    /**
     * 超时重置定时任务 - 定期检查状态是否超时
     */
    @Scheduled(fixedDelayString = "${weight.timeout.check.interval.ms:120000}")
    public void checkTimeout() {
        long now = System.currentTimeMillis();

        // 1. 检查数据接收超时
        if (lastDataReceiveTime > 0) {
            long dataIdleTime = now - lastDataReceiveTime;
            if (dataIdleTime > dataReceiveTimeoutMs) {
                log.warn("数据接收超时, 已{}分钟未收到数据, 重置状态机",
                        String.format("%.1f", dataIdleTime / 60000.0));
                resetToEmptyState();
            }
        }

        // 2. 检查状态超时
        if (currentState != State.EMPTY && currentLoadStartTime > 0) {
            long duration = now - currentLoadStartTime;
            if (duration > stateTimeoutMs) {
                log.warn("状态超时重置, state={}, duration={}ms ({}分钟), 超时阈值={}ms ({}分钟)",
                        currentState,
                        duration, String.format("%.1f", duration / 60000.0),
                        stateTimeoutMs, String.format("%.1f", stateTimeoutMs / 60000.0));

                // 记录未完成的任务信息
                log.warn("未完成的作业信息 - 峰值重量: {}t, 峰值时间: {}, 开始时间: {}",
                        String.format("%.2f", currentPeakWeight),
                        currentPeakTime,
                        currentLoadStartTime);

                resetToEmptyState();
            }
        }
    }

  /*  *//**
     * 每10分钟输出一次统计信息
     *//*
    @Scheduled(fixedDelay = 600000)
    public void printStatistics() {
        log.info("===== 矿卡称重统计 =====");
        log.info("设备编号: {}", equipNo);
        log.info("当前状态: {}", currentState);
        log.info("累计卸车次数: {}", totalUnloadCount.get());
        log.info("累计运载物料: {} 吨", String.format("%.2f", totalMaterialWeight));
        log.info("当前峰值重量: {} 吨", String.format("%.2f", currentPeakWeight));
        log.info("最后数据接收时间: {}", lastDataReceiveTime > 0 ? lastDataReceiveTime : "未收到数据");
        log.info("最后卸车时间: {}", lastUnloadTime > 0 ? lastUnloadTime : "未卸车");
        log.info("缓冲区大小: {} 字节", buffer.length);
        log.info("======================");
    }*/

    /**
     * 读取串口数据 - 追加模式
     */
    public void readSerialData() {

        // 第一次调用时执行累清操作
        /*if (!hasPerformedClearTotal) {
            // performClearTotal();

        }*/

        InputStream inputStream = serialPortManager.getInputStream();
        if (inputStream == null) {
            log.error("串口输入流为空");
            return;
        }

        try {
            // 读取本次所有可用数据
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
            byte[] tempBuffer = new byte[1024];
            int totalBytesRead = 0;

            while (inputStream.available() > 0) {
                int bytesRead = inputStream.read(tempBuffer);
                if (bytesRead > 0) {
                    byteOutputStream.write(tempBuffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    // 防止异常数据无限读取
                    if (totalBytesRead > 4096) {
                        log.warn("单次读取数据超过4KB，可能数据异常，停止读取");
                        break;
                    }
                }
            }

            byte[] newData = byteOutputStream.toByteArray();

            if (newData.length == 0) {
                log.debug("本次读取无数据");
                return;
            }

            log.info("收到原始数据，字节数: {}", newData.length);
            log.debug("原始数据 HEX: {}", bytesToHex(newData));

            // 更新最后接收数据时间
            lastDataReceiveTime = System.currentTimeMillis();

            // ✅ 追加到缓冲区
            byte[] newBuffer = new byte[buffer.length + newData.length];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            System.arraycopy(newData, 0, newBuffer, buffer.length, newData.length);
            buffer = newBuffer;

            log.debug("缓冲区大小: {} 字节", buffer.length);

            // 循环处理缓冲区中的所有完整帧
            processBuffer();

        } catch (IOException e) {
            log.error("读取串口数据错误: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理缓冲区中的数据 - 帧同步和解析
     */
    private void processBuffer() {
        int frameProcessed = 0;

        while (true) {
            // 1. 查找帧头位置
            int headerPos = findHeader(buffer);

            // 没有找到帧头
            if (headerPos < 0) {
                // 保留最后几个字节（可能帧头跨包），防止数据丢失
                if (buffer.length > EXPECTED_HEADER.length) {
                    log.warn("未找到帧头，丢弃 {} 字节数据", buffer.length - EXPECTED_HEADER.length);
                    buffer = Arrays.copyOfRange(buffer, buffer.length - EXPECTED_HEADER.length, buffer.length);
                }
                break;
            }

            // 2. 丢弃帧头前的脏数据
            if (headerPos > 0) {
                log.warn("丢弃帧头前的错位数据: {} 字节, HEX={}",
                        headerPos, bytesToHex(Arrays.copyOfRange(buffer, 0, Math.min(headerPos, 50))));
                buffer = Arrays.copyOfRange(buffer, headerPos, buffer.length);
                continue;
            }

            // 3. 检查数据是否足够一帧
            if (buffer.length < FRAME_LEN) {
                log.debug("缓冲区数据不足一帧，等待更多数据: {} < {}", buffer.length, FRAME_LEN);
                break;
            }

            // 4. 取出一帧数据
            byte[] frame = Arrays.copyOfRange(buffer, 0, FRAME_LEN);

            // 5. 从缓冲区移除已处理的数据
            buffer = Arrays.copyOfRange(buffer, FRAME_LEN, buffer.length);

            // 6. 解析帧
            boolean parseSuccess = parseFrame(frame);

            if (parseSuccess) {
                frameProcessed++;
            } else {
                // CRC校验失败，可能是帧同步错位，继续查找下一帧
                log.warn("帧解析失败，继续同步下一帧");
            }
        }

        if (frameProcessed > 0) {
            log.info("成功处理 {} 个完整帧", frameProcessed);
        }
    }

    /**
     * 查找帧头位置
     */
    private int findHeader(byte[] data) {
        if (data.length < EXPECTED_HEADER.length) {
            return -1;
        }

        for (int i = 0; i <= data.length - EXPECTED_HEADER.length; i++) {
            boolean match = true;
            for (int j = 0; j < EXPECTED_HEADER.length; j++) {
                if (data[i + j] != EXPECTED_HEADER[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (i > 0) {
                    log.debug("在位置 {} 找到帧头", i);
                }
                return i;
            }
        }
        return -1;
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
            sb.append("... (共").append(bytes.length).append("字节)");
        }
        return sb.toString().trim();
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
     * 解析数据帧
     */
    private boolean parseFrame(byte[] frame) {
        if (frame.length != FRAME_LEN) {
            log.warn("帧长度异常 len={}", frame.length);
            return false;
        }

        log.info("完整帧 HEX={}", bytesToHex(frame));

        // 检查帧头
        boolean headerMatch = true;
        for (int i = 0; i < EXPECTED_HEADER.length; i++) {
            if (frame[i] != EXPECTED_HEADER[i]) {
                if (headerMatch) {
                    log.warn("帧头第 {} 位不匹配: 期望={} 实际={}",
                            i,
                            String.format("%02X", EXPECTED_HEADER[i]),
                            String.format("%02X", frame[i]));
                }
                headerMatch = false;
                break;
            }
        }

        if (!headerMatch) {
            log.warn("帧头不匹配，跳过此帧");
            return false;
        }

        // CRC校验
        int crcRecv = (frame[13] & 0xFF) | ((frame[14] & 0xFF) << 8);
        int crcCalc = crc16Modbus(Arrays.copyOfRange(frame, 0, 13));

        log.info("CRC recv=0x{:04X} calc=0x{:04X}", crcRecv, crcCalc);

        if (crcRecv != crcCalc) {
            log.error("CRC 校验失败，跳过此帧");
            return false;
        }

        // 解析重量数据 (字节 7-10)
        ByteBuffer buffer = ByteBuffer.wrap(Arrays.copyOfRange(frame, 7, 11));
        buffer.order(ByteOrder.BIG_ENDIAN);
        int weightKg = buffer.getInt();
        double weightT = weightKg / 1000.0;

        log.info("解析到重量: {} kg ({} t)", weightKg, String.format("%.3f", weightT));

        // 状态机处理
        processWeightByStateMachine(weightT);

        return true;
    }

    /**
     * 状态机处理重量数据
     */
    private void processWeightByStateMachine(double weightT) {
        switch (currentState) {
            case EMPTY:
                handleEmptyState(weightT);
                break;

            case LOADING:
                handleLoadingState(weightT);
                break;

            case UNLOADING:
                handleUnloadingState(weightT);
                break;

            default:
                log.warn("未知状态: {}", currentState);
                resetToEmptyState();
        }
    }

    /**
     * 处理空车状态
     */
    private void handleEmptyState(double weightT) {
        if (weightT >= weightThreshold) {
            // 开始装车 - 记录作业开始时间
            currentState = State.LOADING;
            currentLoadStartTime = System.currentTimeMillis();  // 作业开始时间
            currentPeakWeight = weightT;
            currentPeakTime = currentLoadStartTime;
            lastFuel = tboxDataService.getFuelTotal();

            // 调用工具类记录重量
            MineCartWeighTool.processWeight(equipNo, weightT);

            log.info("===== 开始装车 (作业开始) =====");
            log.info("当前重量: {}t, 作业开始时间: {}, 阈值: {}t",
                    String.format("%.3f", weightT),
                    currentLoadStartTime,
                    weightThreshold);
        } else {
            log.debug("空车状态, 当前重量: {}t (阈值: {}t)",
                    String.format("%.3f", weightT), weightThreshold);
        }
    }

    /**
     * 处理装车状态
     */
    private void handleLoadingState(double weightT) {
        // 更新峰值
        if (weightT > currentPeakWeight) {
            currentPeakWeight = weightT;
            currentPeakTime = System.currentTimeMillis();
            log.info("峰值更新: {}t at {}",
                    String.format("%.3f", currentPeakWeight),
                    currentPeakTime);
        }

        // 调用工具类处理重量
        MineCartWeighTool.processWeight(equipNo, weightT);

        // 检测是否开始卸车（重量降到阈值以下）
        if (weightT < weightThreshold) {
            currentState = State.UNLOADING;
            handleUnloadComplete();  // 触发卸车完成逻辑
        } else {
            long elapsedTime = System.currentTimeMillis() - currentLoadStartTime;
            log.debug("装车中, 当前重量: {}t, 峰值: {}t, 已持续: {}秒",
                    String.format("%.3f", weightT),
                    String.format("%.3f", currentPeakWeight),
                    elapsedTime / 1000);
        }
    }

    /**
     * 处理卸车状态
     */
    private void handleUnloadingState(double weightT) {
        // 等待完全卸完，防止重复触发
        if (weightT >= weightThreshold) {
            // 异常：卸车过程中又变重，可能是数据抖动或新的装车开始
            log.warn("卸车状态检测到重量回升: {}t (阈值: {}t), 重新进入装车状态",
                    String.format("%.3f", weightT), weightThreshold);
            currentState = State.LOADING;
            currentPeakWeight = weightT;
            currentPeakTime = System.currentTimeMillis();
            MineCartWeighTool.processWeight(equipNo, weightT);
        } else {
            long unloadingTime = System.currentTimeMillis() - currentPeakTime;
            log.debug("卸车中, 当前重量: {}t, 已持续: {}秒",
                    String.format("%.3f", weightT),
                    unloadingTime / 1000);
        }
    }

    /**
     * 处理卸车完成（作业结束）
     */
    private void handleUnloadComplete() {
        long unloadCompleteTime = System.currentTimeMillis();  // 卸车完成时间 = 作业结束时间

        // 计算本次作业时长（毫秒）
        long operationDuration = unloadCompleteTime - currentLoadStartTime;  // 作业时长 = 结束时间 - 开始时间

        // 其他统计信息
        long transportDuration = unloadCompleteTime - currentPeakTime;       // 运输时长（峰值到卸车完成）
        long intervalSinceLast = (lastUnloadTime == 0) ? 0 : unloadCompleteTime - lastUnloadTime;  // 两次作业间隔

        log.info("===== 卸车完成 (作业结束) =====");
        log.info("设备编号: {}", equipNo);
        log.info("峰值重量: {} t", String.format("%.2f", currentPeakWeight));
        log.info("作业开始时间: {}", currentLoadStartTime);
        log.info("作业结束时间(sampleTime): {}", unloadCompleteTime);
        log.info("本次作业时长(recvTime): {} ms ({} 秒)", operationDuration, operationDuration / 1000.0);
        log.info("运输时长: {} ms ({} 秒)", transportDuration, transportDuration / 1000.0);
        log.info("距离上次作业间隔: {} ms ({} 秒)", intervalSinceLast, intervalSinceLast / 1000.0);

        // 获取实时油耗
        double curFuelTotal = tboxDataService.getFuelTotal();
        double fuelConsumed = (lastFuel == 0) ? 0 : curFuelTotal - lastFuel;
        log.info("本次油耗: {} L", String.format("%.2f", fuelConsumed));

        // 计算实际重量（使用工具类）
        double realWeight = MineCartWeighTool.calculateRealWeight(equipNo);

        // 更新统计数据
        totalUnloadCount.incrementAndGet();
        totalMaterialWeight += currentPeakWeight;

        // 构建发送数据
        List<DeviceDataVO> deviceDataVOS = new ArrayList<>();

        // 主要重量数据
        DeviceDataVO weightData = new DeviceDataVO();
        weightData.setEquipNum(equipNo);
        weightData.setPointNum("kaugnche");
        weightData.setParamNum("kaungche_weight");
        weightData.setValue(realWeight);
        weightData.setSampleTime(unloadCompleteTime);     // sampleTime = 卸车完成时间（作业结束时间）
        weightData.setRecvTime(operationDuration);        // recvTime = 本次作业时长（毫秒）
        weightData.setFuelTotal(fuelConsumed);
        deviceDataVOS.add(weightData);

        // 可选：添加额外的统计字段（如果需要）
        addAdditionalFields(deviceDataVOS, unloadCompleteTime, operationDuration, transportDuration, intervalSinceLast, currentPeakWeight);

        // 发送到Kafka
        if (DataConfigManager.getInstance().isSampleFlag()) {
            messageSendService.batchSendMsg2Kafka("kaugnche", deviceDataVOS);
            log.info("已发送卸车数据到Kafka - 重量: {}t, sampleTime(结束时间)={}, recvTime(作业时长)={}ms",
                    String.format("%.2f", realWeight), unloadCompleteTime, operationDuration);
        } else {
            log.info("采样标志为false，不发送数据");
        }

        // 更新状态
        lastUnloadTime = unloadCompleteTime;

        // 重置到空车状态
        resetToEmptyState();
    }

    /**
     * 添加额外字段（如果需要记录更多信息）
     */
    private void addAdditionalFields(List<DeviceDataVO> dataList, long completeTime,
                                     long operationDuration, long transportDuration,
                                     long intervalSinceLast, double peakWeight) {
        // 这里可以根据需要添加更多数据点
        // 示例：记录作业时长（毫秒）
        // DeviceDataVO durationData = new DeviceDataVO();
        // durationData.setEquipNum(equipNo);
        // durationData.setPointNum("kaugnche");
        // durationData.setParamNum("operation_duration_ms");
        // durationData.setValue((double) operationDuration);
        // durationData.setSampleTime(completeTime);
        // durationData.setRecvTime(operationDuration);
        // dataList.add(durationData);

        // 示例：记录峰值重量
        // DeviceDataVO peakData = new DeviceDataVO();
        // peakData.setEquipNum(equipNo);
        // peakData.setPointNum("kaugnche");
        // peakData.setParamNum("peak_weight_t");
        // peakData.setValue(peakWeight);
        // peakData.setSampleTime(completeTime);
        // peakData.setRecvTime(operationDuration);
        // dataList.add(peakData);
    }

    /**
     * 发送超时告警
     */
    private void sendTimeoutAlert() {
        try {
            // 构建告警数据
            List<DeviceDataVO> alertDataList = new ArrayList<>();
            DeviceDataVO alertData = new DeviceDataVO();
            alertData.setEquipNum(equipNo);
            alertData.setPointNum("kaugnche");
            alertData.setParamNum("timeout_alert");
            alertData.setValue(1.0);  // 1表示超时
            alertData.setSampleTime(System.currentTimeMillis());
            alertData.setRecvTime(0L);
            alertDataList.add(alertData);

            // 可选：发送告警到Kafka
            // messageSendService.batchSendMsg2Kafka("alert", alertDataList);

            log.info("已记录超时告警");
        } catch (Exception e) {
            log.error("发送超时告警失败: {}", e.getMessage());
        }
    }

    /**
     * 重置到空车状态
     */
    private void resetToEmptyState() {
        State previousState = currentState;
        currentState = State.EMPTY;
        currentLoadStartTime = 0;
        currentPeakTime = 0;
        currentPeakWeight = 0;
        log.debug("重置状态: {} -> EMPTY", previousState);
    }

    /**
     * 获取当前重量
     */
    public Double getCurrentWeight() {
        return currentPeakWeight > 0 ? currentPeakWeight : 0.0;
    }

    /**
     * 获取当前状态
     */
    public String getCurrentState() {
        return currentState.name();
    }

    /**
     * 获取本次作业已持续时长（毫秒）
     */
    public long getCurrentDuration() {
        if (currentState != State.EMPTY && currentLoadStartTime > 0) {
            return System.currentTimeMillis() - currentLoadStartTime;
        }
        return 0;
    }

    /**
     * 获取累计卸车次数
     */
    public long getTotalUnloadCount() {
        return totalUnloadCount.get();
    }

    /**
     * 获取累计运载物料总量
     */
    public double getTotalMaterialWeight() {
        return totalMaterialWeight;
    }

    /**
     * 手动重置（用于异常恢复）
     */
    public void manualReset() {
        log.warn("手动重置状态机");
        resetToEmptyState();
        lastUnloadTime = 0;
        lastFuel = 0;
        buffer = new byte[0];
    }

    /**
     * 获取最后卸车时间
     */
    public long getLastUnloadTime() {
        return lastUnloadTime;
    }

    /**
     * 获取缓冲区大小（用于监控）
     */
    public int getBufferSize() {
        return buffer.length;
    }

    /**
     * 清空缓冲区（用于异常恢复）
     */
    public void clearBuffer() {
        log.warn("手动清空缓冲区，原大小: {} 字节", buffer.length);
        buffer = new byte[0];
    }

    @PreDestroy
    public void cleanup() {
        buffer = new byte[0];
        log.info("===== RS485WeightMonitor资源清理完成 =====");
        log.info("最终统计 - 设备: {}, 总卸车次数: {}, 总运载量: {}吨",
                equipNo, totalUnloadCount.get(), String.format("%.2f", totalMaterialWeight));
    }

    /**
     * 发送MODBUS指令（带CRC校验）
     * @param cmdHex 不带CRC的十六进制命令，如 "01 06 00 82 00 00"
     * @return 响应数据
     */
    private byte[] sendModbusCommand(String cmdHex) {
        if (!serialPortManager.isOpen()) {
            log.error("串口未打开，无法发送命令");
            return new byte[0];
        }

        try {
            // 1. 解析十六进制命令
            byte[] rawCommand = hexStringToByteArray(cmdHex);

            // 2. 计算CRC16（Modbus）
            int crc = crc16Modbus(rawCommand);

            // 3. 获取CRC的小端字节序（与Python crc.to_bytes(2, 'little') 一致）
            byte[] crcBytes = new byte[2];
            crcBytes[0] = (byte) (crc & 0xFF);         // 低字节
            crcBytes[1] = (byte) ((crc >> 8) & 0xFF);  // 高字节

            // 4. 构建完整帧（原命令 + CRC小端字节）
            byte[] frame = new byte[rawCommand.length + 2];
            System.arraycopy(rawCommand, 0, frame, 0, rawCommand.length);
            System.arraycopy(crcBytes, 0, frame, rawCommand.length, 2);

            // 4. 发送命令
            log.info("📤 发送: {}", bytesToHex(frame));
            serialPortManager.getOutputStream().write(frame);
            serialPortManager.getOutputStream().flush();

            // 5. 等待响应
            Thread.sleep(100);

            // 6. 读取响应
            InputStream inputStream = serialPortManager.getInputStream();
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            if (inputStream != null && inputStream.available() > 0) {
                byte[] temp = new byte[64];  // 最多读取64字节
                int bytesRead = inputStream.read(temp);
                if (bytesRead > 0) {
                    responseBuffer.write(temp, 0, bytesRead);
                    byte[] response = responseBuffer.toByteArray();
                    log.info("📥 接收: {}", bytesToHex(response));
                    return response;
                }
            } else {
                log.warn("未收到命令响应");
            }

        } catch (Exception e) {
            log.error("发送Modbus命令失败: {}", e.getMessage(), e);
        }

        return new byte[0];
    }

    /**
     * 执行累清操作（只在程序启动后第一次读取时执行）
     */
    private void performClearTotal() {
        log.info("\n🚨 执行累清操作");
        byte[] response = sendModbusCommand("01 06 00 82 00 00");

        if (response.length >= 8) {
            hasPerformedClearTotal = true;
            log.info("✅ 累清成功");
        } else {
            log.warn("❌ 累清失败, 响应长度: {} 字节", response.length);
        }
        currentLoadStartTime = System.currentTimeMillis();
    }

    /**
     * 十六进制字符串转字节数组
     * 支持 "01 06 00 82 00 00" 或 "010600820000" 格式
     */
    private byte[] hexStringToByteArray(String hex) {
        String hexWithoutSpaces = hex.replaceAll("\\s", "");
        int len = hexWithoutSpaces.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexWithoutSpaces.charAt(i), 16) << 4)
                    + Character.digit(hexWithoutSpaces.charAt(i + 1), 16));
        }
        return data;
    }
}