package com.chaos.mine.service;

import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.runner.SerialConfig;
import com.chaos.mine.runner.SerialPortManager;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class ADCMonitorService {

    private static final String ADC_NODE = "/sys/devices/platform/fec10000.saradc/iio:device0/";
    private static final double VOLTAGE_SCALE = 15.0 / 4096.0;  // 电压换算系数

    @Autowired
    private MessageSendService messageSendService;

    @Value("${equip.no:test}")
    private String equipNo;

    @Value("${switch2.serial.port.name:COM4}")
    String portName;


    @Autowired
    private SerialPortManager serialPortManager;

    @Autowired
    private SerialConfig serialConfig;


    // 缓存最新的开关量值
    private final Map<String, Integer> switchCache = new HashMap<>();

    /**
     * 每秒执行一次的定时任务
     */
    /*@Scheduled(fixedRate = 10000)
    @Async*/// 1000毫秒 = 1秒
    public void monitorADC() {
        try {
            // 读取ADC原始值
            int rawValue2 = readRawADCValue("in_voltage2_raw");
            int rawValue4 = readRawADCValue("in_voltage4_raw");
            int rawValue6 = readRawADCValue("in_voltage6_raw");
            List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
            // 计算电压值
            double voltage2 = VOLTAGE_SCALE * rawValue2;
            DeviceDataVO feishi = new DeviceDataVO();
            feishi.setEquipNum(equipNo);
            feishi.setPointNum("switch");
            feishi.setParamNum("feishi");
            feishi.setValue(voltage2);
            feishi.setSampleTime(System.currentTimeMillis());
            feishi.setRecvTime(System.currentTimeMillis());
            deviceDataVOS.add(feishi);
            double voltage4 = VOLTAGE_SCALE * rawValue4;
            DeviceDataVO kuangshi = new DeviceDataVO();
            kuangshi.setEquipNum(equipNo);
            kuangshi.setPointNum("switch");
            kuangshi.setParamNum("kuangshi");
            kuangshi.setValue(voltage4);
            kuangshi.setSampleTime(System.currentTimeMillis());
            kuangshi.setRecvTime(System.currentTimeMillis());
            deviceDataVOS.add(kuangshi);
            double voltage6 = VOLTAGE_SCALE * rawValue6;
            DeviceDataVO other = new DeviceDataVO();
            other.setEquipNum(equipNo);
            other.setPointNum("switch");
            other.setParamNum("other");
            other.setValue(voltage6);
            other.setSampleTime(System.currentTimeMillis());
            other.setRecvTime(System.currentTimeMillis());
            deviceDataVOS.add(other);

            log.info("get switch1 :{}", JSON.toJSONString(deviceDataVOS));
            if (DataConfigManager.getInstance().isSampleFlag()) {
                messageSendService.batchSendMsg2Kafka("switch", deviceDataVOS);
            }

        } catch (IOException e) {
            log.error("读取ADC节点失败: {}", e.getMessage());
        }
    }

    /**
     * 从ADC节点读取原始值
     */
    private int readRawADCValue(String type) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(ADC_NODE + type))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return Integer.parseInt(line.trim());
            } else {
                throw new IOException("ADC节点返回空值");
            }
        } catch (NumberFormatException e) {
            throw new IOException("ADC节点返回无效的数字格式: " + e.getMessage());
        }
    }


    /*public void readSwitch2() {
        int slave = 0x02;
        int func = 0x02;
        int startAddr = 0x0000;
        int count = 0x0004;

        SerialPort ser = SerialPort.getCommPort(portName);
        ser.setBaudRate(38400);
        ser.setNumDataBits(8);
        ser.setParity(SerialPort.NO_PARITY);
        ser.setNumStopBits(1);
        ser.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        if (!ser.openPort()) {
            System.err.println("无法打开串口 " + portName);
            return;
        }

        try {
            // 构建请求帧
            byte[] frame = new byte[8];
            frame[0] = (byte) slave;          // 从站地址
            frame[1] = (byte) func;            // 功能码
            frame[2] = (byte) (startAddr >> 8); // 起始地址高字节
            frame[3] = (byte) (startAddr);      // 起始地址低字节
            frame[4] = (byte) (count >> 8);     // 数量高字节
            frame[5] = (byte) (count);           // 数量低字节

            // 计算 CRC
            int crc = modbusCRC(Arrays.copyOf(frame, 6));
            frame[6] = (byte) (crc & 0xFF);        // CRC 低字节
            frame[7] = (byte) ((crc >> 8) & 0xFF); // CRC 高字节

            // 清空输入缓冲区
            ser.flushIOBuffers();

            // 发送请求
            ser.writeBytes(frame, frame.length);
            log.info("发送: {}", toHex(frame));

            // 读取响应 (最多 100 字节)
            byte[] resp = new byte[100];
            int bytesRead = ser.readBytes(resp, resp.length);

            if (bytesRead > 0) {
                byte[] actualResp = Arrays.copyOf(resp, bytesRead);
                log.info("返回(hex): {}", toHex(actualResp));

                if (actualResp.length >= 5) {
                    int byteCount = actualResp[2] & 0xFF;  // 数据字节数
                    if (byteCount >= 1) {
                        int b = actualResp[3] & 0xFF;
                        int di0 = (b >> 0) & 1;
                        int di1 = (b >> 1) & 1;
                        int di2 = (b >> 2) & 1;
                        int di3 = (b >> 3) & 1;
                        log.info("DI0~DI3: " + di0 + " " + di1 + " " + di2 + " " + di3);
                    }
                }
            } else {
                log.info("无响应");
            }
        } catch (Exception e) {
            log.info(e.getMessage());
        } finally {
            ser.closePort();
        }
    }

    // 计算 Modbus CRC16
    public static int modbusCRC(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc;
    }

    // 将字节数组转为 HEX 字符串
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }*/

    public void readSwitch2WithDynamicBaudrate() {
        try {
            // 保存当前波特率
            int originalBaudrate = serialConfig.getBaudRate();

            // 临时修改波特率为38400
            log.info("临时修改波特率: {} -> 38400", originalBaudrate);

            // 需要SerialPortManager支持动态修改波特率
            // 这里假设SerialPortManager有reconfigure方法
            boolean reconfigured = serialPortManager.reconfigurePort(38400, 8, 1, 0);

            if (!reconfigured) {
                log.error("重新配置串口失败");
                return;
            }

            // 执行读取操作
            readSwitch2();

            // 恢复原始波特率
            log.info("恢复波特率: 38400 -> {}", originalBaudrate);
            serialPortManager.reconfigurePort(originalBaudrate, 8, 1, 0);

        } catch (Exception e) {
            log.error("动态波特率读取失败", e);
        }
    }


    public void readSwitch2() {
        InputStream in = null;
        OutputStream out = null;

        try {
            // 确保串口可用
            if (!serialPortManager.ensureSerialPortOpen()) {
                log.error("串口不可用，无法读取开关量");
                return;
            }

            in = serialPortManager.getInputStream();
            out = serialPortManager.getOutputStream();

            if (in == null || out == null) {
                log.error("获取串口流失败");
                return;
            }

            // 构建请求帧
            byte[] frame = buildSwitchRequestFrame();

            // 清空输入缓冲区
            serialPortManager.clearInputStream();

            // 发送请求
            out.write(frame);
            out.flush();
            log.info("发送: {}", bytesToHex(frame));

            // 读取响应
            byte[] response = readSwitchResponse(in);

            if (response.length > 0) {
                log.info("返回(hex): {}", bytesToHex(response));
                parseSwitchResponse(response);
            } else {
                log.info("无响应");
            }

        } catch (Exception e) {
            log.error("读取开关量失败", e);
        } finally {
            // 关键：释放资源
            serialPortManager.release();
        }
    }

    /**
     * 构建开关量读取请求帧
     */
    private byte[] buildSwitchRequestFrame() {
        int slave = 0x02;
        int func = 0x02;
        int startAddr = 0x0000;
        int count = 0x0004;

        byte[] frame = new byte[8];
        frame[0] = (byte) slave;
        frame[1] = (byte) func;
        frame[2] = (byte) (startAddr >> 8);
        frame[3] = (byte) (startAddr);
        frame[4] = (byte) (count >> 8);
        frame[5] = (byte) (count);

        int crc = calculateModbusCRC(Arrays.copyOf(frame, 6));
        frame[6] = (byte) (crc & 0xFF);
        frame[7] = (byte) ((crc >> 8) & 0xFF);
        return frame;
    }

    /**
     * 读取开关量响应
     */
    private byte[] readSwitchResponse(InputStream in) throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];

        long startTime = System.currentTimeMillis();
        int timeout = serialConfig.getReadTimeout();
        int frameTimeout = serialConfig.getFrameTimeout();

        long lastDataTime = System.currentTimeMillis();
        boolean readingFrame = true;

        while (readingFrame && System.currentTimeMillis() - startTime < timeout) {
            if (in.available() > 0) {
                int read = in.read(buffer);
                if (read > 0) {
                    response.write(buffer, 0, read);
                    lastDataTime = System.currentTimeMillis();

                    byte[] currentData = response.toByteArray();
                    if (isSwitchFrameComplete(currentData)) {
                        readingFrame = false;
                    }
                }
            } else {
                if (System.currentTimeMillis() - lastDataTime > frameTimeout) {
                    if (response.size() > 0) {
                        readingFrame = false;
                    }
                }
            }
            Thread.sleep(2);
        }

        if (response.size() == 0) {
            log.warn("读取响应超时");
            return new byte[0];
        }

        return response.toByteArray();
    }

    /**
     * 判断开关量帧是否完整
     */
    private boolean isSwitchFrameComplete(byte[] data) {
        if (data.length < 3) return false;

        byte functionCode = data[1];

        if (functionCode == 0x02) {
            if (data.length < 3) return false;
            int byteCount = data[2] & 0xFF;
            return data.length >= (3 + byteCount + 2);
        }

        return false;
    }

    /**
     * 验证CRC
     */
    private boolean verifySwitchCRC(byte[] data) {
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

    /**
     * 解析开关量响应
     */
    private void parseSwitchResponse(byte[] response) {
        if (response.length < 5) {
            log.warn("响应数据长度不足: {}", response.length);
            return;
        }

        if (!verifySwitchCRC(response)) {
            log.error("CRC校验失败");
            return;
        }

        byte funcCode = response[1];
        if (funcCode != 0x02) {
            log.warn("响应功能码异常: 期望=0x02, 实际=0x{}",
                    String.format("%02X", funcCode));
            return;
        }

        int byteCount = response[2] & 0xFF;

        if (byteCount >= 1 && response.length >= 3 + byteCount + 2) {
            int dataByte = response[3] & 0xFF;

            int di0 = (dataByte >> 0) & 1;
            int di1 = (dataByte >> 1) & 1;
            int di2 = (dataByte >> 2) & 1;
            int di3 = (dataByte >> 3) & 1;

            log.info("DI0~DI3: {} {} {} {}", di0, di1, di2, di3);

            handleSwitchValues(di0, di1, di2, di3);
        }
    }

    /**
     * 计算 Modbus CRC16
     */
    private int calculateModbusCRC(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);  // XOR with byte
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) {  // 检查最低位
                    crc >>= 1;
                    crc ^= 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;  // 确保返回16位值
    }

    /**
     * 将字节数组转为 HEX 字符串
     */
    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    /**
     * 处理开关量值
     */
    private void handleSwitchValues(int di0, int di1, int di2, int di3) {
        // 发送到Kafka
        if (DataConfigManager.getInstance().isSampleFlag()) {
            List<DeviceDataVO> dataList = new ArrayList<>();
            long now = System.currentTimeMillis();

            // 矿石
            if (switchCache.get("DI0") == null || switchCache.get("DI0") != di0) {
                addSwitchData(dataList, "kuangshi", di0, now);
                switchCache.put("DI0", di0);
            }

            // 废石
            if (switchCache.get("DI1") == null || switchCache.get("DI1") != di1) {
                addSwitchData(dataList, "feishi", di1, now);
                switchCache.put("DI1", di1);
            }

            // 其他
            if (switchCache.get("DI2") == null || switchCache.get("DI2") != di2) {
                addSwitchData(dataList, "other", di2, now);
                switchCache.put("DI2", di2);
            }

        /*    if (switchCache.get("DI3") == null || switchCache.get("DI3") != di3) {
                addSwitchData(dataList, "DI3", di3, now);
                switchCache.put("DI3", di3);
            }
            */
            if (!dataList.isEmpty()) {
                messageSendService.batchSendMsg2Kafka("switch", dataList);
            } else {
                log.info("switch2 data list is empty");
            }
        }
    }

    /**
     * 添加开关量数据
     */
    private void addSwitchData(List<DeviceDataVO> dataList, String paramNum, int value, long time) {
        DeviceDataVO data = new DeviceDataVO();
        data.setEquipNum(equipNo);
        data.setPointNum("switch");
        data.setParamNum(paramNum);
        data.setValue((double) value);
        data.setSampleTime(time);
        data.setRecvTime(time);
        dataList.add(data);
    }
}