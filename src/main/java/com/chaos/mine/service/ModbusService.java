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

    @Autowired
    private TboxDataService tboxDataService;

    @Value("${equip.no:test}")
    private String equipNo;

    private volatile  double lastSingle;
    private volatile long lastTime;
    private volatile double lastFuel=0;
    private volatile boolean lastFuelValid = false;      // 油耗基准是否已成功获取（与hasPerformedClearTotal解耦，互不阻塞重试）
    @Value("${fuel.single.cycle.max.liter:50}")
    private double maxReasonableFuelPerCycle;             // 单次作业合理油耗上限(L)，超过大概率是基准异常导致，先兜底拦一道
    // 在类中添加一个标志位，记录是否已经执行过累清操作
    private volatile boolean hasPerformedClearTotal = false;
    private final AtomicReference<Double> singleWeight = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalWeight = new AtomicReference<>(0.0);
    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong atomicLong = new AtomicLong(0L);

    /**
     * 执行累清操作（只在程序启动后第一次读取时执行）
     */
    private boolean performClearTotal() {
        log.info("\n🚨 执行累清操作");
        byte[] response = sendModbusCommand("01 06 00 82 00 00");

        if (response.length >= 8) {
            log.info("✅ 累清成功");
            return true;
        } else {
            log.warn("❌ 累清失败, 响应长度: {} 字节, 下次采集会重试", response.length);
            return false;
        }
    }

    /**
     * 安全获取OBD累计油耗，异常时返回NaN，调用方需判断，不影响主流程
     */
    private double safeGetFuelTotal() {
        try {
            return tboxDataService.getFuelTotal();
        } catch (Exception e) {
            log.error("获取OBD累计油耗失败: {}", e.getMessage());
            return Double.NaN;
        }
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



    public synchronized void readWeights() {
        if (!hasPerformedClearTotal) {
            boolean cleared = performClearTotal();
            lastTime = System.currentTimeMillis();
            // 只有真的清零成功才置位；失败的话保持false，下一轮readWeights会自动重试，
            // 不会像之前那样第一次失败就永久放弃（问题2）
            if (cleared) {
                hasPerformedClearTotal = true;
            }
        }
        // 油耗基准初始化跟累清是两件独立的事，用独立的标志位+安全读取，
        // 一次获取失败不会导致这一轮采集整体中断，也不会永久卡死在lastFuel=0（问题4）
        if (!lastFuelValid) {
            double fuel = safeGetFuelTotal();
            if (!Double.isNaN(fuel)) {
                lastFuel = fuel;
                lastFuelValid = true;
            } else {
                log.warn("油耗基准获取失败，下一轮会重试");
            }
        }

        try {
            // 确保串口可用
            if (!serialPortManager.ensureSerialPortOpen()) {
                log.error("串口不可用");
                return;
            }

            // 获取输出流和输入流（该串口为本设备专属，不与其他任务共用，
            // 保持长连接，不在每轮采集结束后关闭——避免频繁开关串口带来的时序问题和额外开销）
            OutputStream out = serialPortManager.getOutputStream();
            InputStream in = serialPortManager.getInputStream();

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
                log.info("Single weight: {}", weight);
                double curFuelTotal = safeGetFuelTotal();
                boolean fuelReadOk = lastFuelValid && !Double.isNaN(curFuelTotal);
                double fuelConsumed = 0;

                if (fuelReadOk) {
                    fuelConsumed = curFuelTotal - lastFuel;
                    if (fuelConsumed < 0) {
                        // OBD计数器理论上单调递增，出现负数大概率是计数器被重置
                        log.warn("油耗计算出现负值(lastFuel={}, curFuelTotal={})，本次记0",
                                String.format("%.2f", lastFuel), String.format("%.2f", curFuelTotal));
                        fuelConsumed = 0;
                    } else if (fuelConsumed > maxReasonableFuelPerCycle) {
                        // 单次作业不可能消耗这么多油，大概率是lastFuel基准异常（例如曾经卡在0，
                        // 这次算出来的是整车累计油耗）。先按0处理，避免污染这一条记录，
                        // 同时打印足够信息方便定位是哪次基准出的问题
                        log.warn("单次油耗{}超过合理上限{}L，怀疑基准异常(lastFuel={}, curFuelTotal={})，本次记0",
                                String.format("%.2f", fuelConsumed), maxReasonableFuelPerCycle,
                                String.format("%.2f", lastFuel), String.format("%.2f", curFuelTotal));
                        fuelConsumed = 0;
                    } else if (fuelConsumed == 0) {
                        // 业务确认：0是不合法的值，短时间内OBD计数器未跳表时用0.5兜底
                        fuelConsumed = 0.5;
                    }
                } else {
                    log.warn("油耗基准或当前读数无效，本次油耗记为0（lastFuelValid={}）", lastFuelValid);
                }

                DeviceDataVO single = new DeviceDataVO();
                single.setEquipNum(equipNo);
                single.setPointNum("02");
                single.setParamNum("singleWeight");
                single.setValue(weight);
                single.setSampleTime(System.currentTimeMillis());
                single.setRecvTime(lastTime);
                single.setFuelTotal(fuelConsumed);
                deviceDataVOS.add(single);
                if (lastSingle != weight) {
                    sendFlag = true;
                    lastSingle = weight;
                    lastTime = System.currentTimeMillis();
                    // 每条记录都是独立、已闭环的作业周期，不能把油耗挪到下一条上去算，
                    // 所以这里每次都要推进基线——不管这次差值是0还是超过上限，
                    // 否则下一条记录的差值会把这次的量级也叠加进去，造成新的异常
                    if (fuelReadOk) {
                        lastFuel = curFuelTotal;
                    }
                }
                /*if (weight < 2) {
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
                }*/
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