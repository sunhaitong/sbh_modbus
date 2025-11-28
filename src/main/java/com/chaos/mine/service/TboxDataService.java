package com.chaos.mine.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.chaos.mine.entity.TBoxSignalConstant;
import com.chaos.mine.util.ServerIpUtil;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/14 11:06
 * @Version 1.0
 */
@Slf4j
@Service
public class TboxDataService {

    // 从配置文件注入参数
    @Value("${tbox.serial.port.name}")
    private String portName;
    @Value("${tbox.serial.timeout.read}")
    private int readTimeout;
    @Value("${tbox.tbox.init.delay}")
    private long tboxInitDelay;

    @Autowired
    private MessageSendService messageSendService;

    // 核心组件
    private SerialPort serialPort; // 串口对象
    private final Map<String, Object> signalMap = new HashMap<>(); // 信号存储容器
    private final StringBuilder dataBuffer = new StringBuilder(); // 数据接收缓冲区
    private boolean isTboxReady = false; // TBOX就绪标志
    private long powerOnTime; // 上电时间戳（用于计算初始化耗时）

    // 服务启动时初始化串口与上电时间
    @PostConstruct
    public void initSerial() {
        // 记录上电时间（用于判断TBOX是否就绪）
        powerOnTime = System.currentTimeMillis();
        // 初始化串口
        initSerialPort();
    }

    // 串口初始化（配置参数+打开端口）
    private void initSerialPort() {
        // 获取指定端口
        serialPort = SerialPort.getCommPort(portName);
        // 配置串口参数（严格遵循HPM协议）
        serialPort.setBaudRate(115200);
        serialPort.setNumDataBits(8);
        serialPort.setNumStopBits(1);
        serialPort.setParity(0);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeout, 0);

        // 打开串口（失败抛异常，终止服务启动）
        if (!serialPort.openPort()) {
            throw new RuntimeException("串口初始化失败！端口[" + portName + "]被占用或参数错误");
        }
        log.info("串口初始化成功：端口={}，波特率={}", portName, 115200);
    }


    // -------------------------- 核心：@Scheduled每秒读取串口数据 --------------------------
    @Scheduled(fixedRate = 1000)
    @Async// 固定周期1000ms（1秒）执行一次
    public void readSerialDataPeriodically() {
        // 1. 先判断TBOX是否就绪（上电后超过初始化延时则就绪）
        checkTboxReady();

        // 2. 读取串口数据（单次读取最大1KB，适配协议帧大小）
        byte[] readBuffer = new byte[1024];
        int readLen = serialPort.readBytes(readBuffer, readBuffer.length);

        // 3. 有数据则追加到缓冲区，无数据则直接返回
        if (readLen <= 0) {
            return;
        }
        String receivedData = new String(readBuffer, 0, readLen, StandardCharsets.UTF_8);
        dataBuffer.append(receivedData);

        // 4. 提取并处理完整数据帧（协议规定以\r\n结尾）
        extractAndProcessFrames();
    }

    // 检查TBOX是否就绪（仅初始化一次）
    private void checkTboxReady() {
        if (!isTboxReady) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - powerOnTime >= tboxInitDelay) {
                isTboxReady = true;
                log.info("TBOX初始化完成（耗时：{}秒），开始解析JSON数据", tboxInitDelay / 1000);
            }
        } else {
            log.info("TBOX 不是可读状态");
        }
    }

    // 提取所有完整帧并处理（支持缓冲区中存在多帧数据的场景）
    private void extractAndProcessFrames() {
        String bufferStr = dataBuffer.toString();
        // 循环提取所有以\r\n结尾的完整帧（避免缓冲区积压多帧数据）
        while (bufferStr.contains("\r\n")) {
            int frameEndIndex = bufferStr.indexOf("\r\n");
            // 提取单帧数据（移除\r\n并trim空字符）
            String oneFrame = bufferStr.substring(0, frameEndIndex).trim();
            // 更新缓冲区（移除已处理的帧）
            bufferStr = bufferStr.substring(frameEndIndex + 2);
            dataBuffer.setLength(0);
            dataBuffer.append(bufferStr);

            // 处理单帧数据（空帧直接跳过）
            if (!oneFrame.isEmpty()) {
                processSingleFrame(oneFrame);
            }
        }
    }

    // 处理单帧数据（TBOX就绪后解析JSON，未就绪则丢弃）
    private void processSingleFrame(String oneFrame) {
        if (!isTboxReady) {
            log.info("TBOX未就绪，丢弃数据：{}", oneFrame);
            return;
        }

        // 协议规定JSON帧首尾为{}，先做基础校验
        if (oneFrame.startsWith("{") && oneFrame.endsWith("}")) {
            try {
                // 解析JSON并更新信号存储（覆盖旧值，保持数据最新）
                JSONObject jsonObject = JSON.parseObject(oneFrame);
                for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
                    //signalMap.put(entry.getKey(), entry.getValue());
                    signalMap.put(entry.getKey(), entry.getValue());
                    if (entry.getKey().equals("DM1")) {
                        JSONArray array = jsonObject.getJSONArray("DM1");
                        for (int i = 0; i < array.size(); i++) {
                            messageSendService.sendMsg2Kafka("01", entry.getKey()+ "_" + i, array.getDouble(i));
                        }
                    } else {
                        messageSendService.sendMsg2Kafka("01", entry.getKey(), jsonObject.getDoubleValue(entry.getKey()));
                    }
                }
                log.info("解析成功，当前信号数：{}，最新帧：{}", signalMap.size(), oneFrame);
            } catch (Exception e) {
                log.error("JSON解析失败，无效数据：{}，异常：{}", oneFrame , e.getMessage());
            }
        } else {
            log.error("非JSON格式数据，丢弃：{}", oneFrame);
        }
    }

    public static void main(String[] args) {
        String json = "{\"Eng_Oil_Press\":224,\"Eng_Cool_Temp\":75,\"Eng_In_Air_Temp\":28,\"Eng_Op_Hrs\":58.35,\"Eng_Spd\":850,\"DPF_Regen\":0,\"NOx_Out\":-1,\"NOx_In\":537.8,\"DEF_Level\":98.4,\"Fuel_Total\":403.5,\"Veh_Spd\":2.3,\"Trans_Oil_Press\":2.5,\"Trans_Oil_Temp\":35,\"Batt_Volt\":27.9,\"DM1\":[]}";

        JSONObject jsonObject = JSON.parseObject(json);
        Double test = jsonObject.getDouble("Eng_Oil_Press");
    }

    // 对外提供：获取所有信号值（供接口调用）
    // -------------------------- 核心：返回全量信号（信号名作为Key，无中文） --------------------------
    public Map<String, Object> getAllSignalsWithDefault() {
        // 1. 初始化全量信号+默认值（每次新建Map，避免修改常量Map）
        Map<String, Object> resultMap = TBoxSignalConstant.getDefaultSignalMap();

        // 2. 用真实数据覆盖默认值（加锁确保线程安全）
        synchronized (signalMap) {
            for (Map.Entry<String, Object> realEntry : signalMap.entrySet()) {
                String signalName = realEntry.getKey();
                Object realValue = realEntry.getValue();
                // 仅覆盖存在的信号（避免TBOX发送协议外的无效信号）
                if (resultMap.containsKey(signalName)) {
                    resultMap.put(signalName, realValue);
                }
            }
        }

        return resultMap;
    }

    // 服务销毁时关闭串口（释放硬件资源）
    @PreDestroy
    public void closeSerialPort() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            System.out.println("串口已关闭：" + portName);
        }
    }

}