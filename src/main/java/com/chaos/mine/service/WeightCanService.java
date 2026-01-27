package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.MineCartWeighTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CAN总线重量读取Service（Java 8）
 * 独立Service，可通过@Scheduled注解调用获取重量
 */
@Service
@Slf4j
public class WeightCanService {
    /*// CAN总线配置常量（与Python保持一致）
    private static final String CAN_CHANNEL = "can0";
    private static final int CAN_BITRATE = 250000;
    private static final int WEIGHT_CAN_ID = 0x600;

    // 原子变量存储最新重量（线程安全）
    private final AtomicReference<Double> latestWeightTons = new AtomicReference<>(null);

    // candump进程及读取器（全局维护）
    private Process candumpProcess;
    private BufferedReader candumpReader;
    // 数据解析线程
    private Thread parseThread;

    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong atomicLong = new AtomicLong(0L);

    @Value("${equip.no:test}")
    private String equipNo;

    @Autowired
    private MessageSendService messageSendService;

    *//**
     * 初始化：应用启动时初始化CAN总线+启动数据监听
     * 替代CommandLineRunner，通过@PostConstruct实现
     *//*
    @PostConstruct
    public void initCanBus() {
        try {
            // 1. 停止并重启CAN0（初始化总线）
            execSystemCommand("sudo ip link set " + CAN_CHANNEL + " down");
            execSystemCommand("sudo ip link set " + CAN_CHANNEL + " up type can bitrate " + CAN_BITRATE);
            log.info("CAN总线 " + CAN_CHANNEL + " 初始化完成");

            // 2. 启动candump进程
            startCandumpProcess();
        } catch (Exception e) {
            throw new RuntimeException("CAN总线初始化失败", e);
        }
    }

    *//**
     * 启动candump进程并开启异步解析线程
     *//*
    private void startCandumpProcess() throws IOException {
        // 启动candump命令（仅监听目标CAN ID，减少数据量）
        candumpProcess = Runtime.getRuntime().exec(new String[]{"candump", CAN_CHANNEL, "--canid=" + WEIGHT_CAN_ID});
        candumpReader = new BufferedReader(new InputStreamReader(candumpProcess.getInputStream()));

        // 启动异步解析线程（Java 8 匿名内部类）
        parseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String line;
                try {
                    while ((line = candumpReader.readLine()) != null) {
                        log.info("candumpReader............");
                        parseCandumpLine(line); // 解析每行数据并更新最新重量
                    }
                } catch (IOException e) {
                    // 进程正常退出时忽略异常，异常退出则打印日志
                    if (!candumpProcess.isAlive()) {
                        System.err.println("CAN数据监听进程已退出: " + e.getMessage());
                    }
                }
            }
        });
        parseThread.setDaemon(true); // 守护线程，应用退出时自动终止
        parseThread.start();

        System.out.println("CAN数据监听已启动，等待重量数据...");
    }

    *//**
     * 解析candump输出行，更新最新重量
     * 完全复刻Python的解析逻辑
     *//*
    private void parseCandumpLine(String line) {
        log.info("parseCandumpLine: " + line);
        // candump输出格式示例：can0  600   [8]  12 34 56 00 00 00 00 00
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4) {
            log.info("parts.length < 4");
            return;
        }

        // 解析CAN ID（16进制转10进制）
        int canId;
        try {
            canId = Integer.parseInt(parts[1], 16);
        } catch (NumberFormatException e) {
            log.info("canId parse error < 4");
            return;
        }

        // 过滤目标CAN ID（双重校验）
        if (canId != WEIGHT_CAN_ID) {
            log.info("canId != WEIGHT_CAN_ID");
            return;
        }

        // 解析数据字节（取前3字节）
        String[] hexBytes = parts[4].split(" ");
        if (hexBytes.length < 3) {
            log.info("hexBytes.length < 3");
            return;
        }

        // 16进制转字节数组（Java 8 循环）
        byte[] data = new byte[3];
        try {
            for (int i = 0; i < 3; i++) {
                data[i] = (byte) Integer.parseInt(hexBytes[i], 16);
            }
        } catch (NumberFormatException e) {
            log.info("16进制转字节数组错误");
            return;
        }

        // 解析重量并更新原子变量
        Double tons = parseWeightTons(data);
        if (tons != null) {
            latestWeightTons.set(tons);
        } else {
            log.info("tons parse error");
        }
    }

    *//**
     * 重量解析核心方法（与Python逻辑完全一致）
     *//*
    private Double parseWeightTons(byte[] data) {
        if (data == null || data.length < 3) {
            return null;
        }

        // 小端序解析24位数据（消除Java字节符号位影响）
        int raw = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16);

        // 24bit补码负数处理
        if ((raw & 0x800000) != 0) {
            raw -= (1 << 24);
        }

        // 单位转换：0.1kg → kg → 吨
        double kg = raw * 0.1;
        return kg / 1000.0;
    }

    *//**
     * 对外提供的获取最新重量方法（供@Scheduled调用）
     *//*
    public void getLatestWeightTons() {
        Double tons = latestWeightTons.get();
        if (tons != null) {
            log.info("{} 最新重量: {}} 吨", LocalDateTime.now(), tons);
            List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
            boolean sendFlag = false;
            if (tons < 2) {
                log.info("weight < 2 send:{}", tons);
                if (atomicBoolean.get()) {
                    log.info("Single weight: {}", tons);
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
                log.info("current weight: {}", tons);
                MineCartWeighTool.processWeight(equipNo, tons);
                atomicBoolean.set(true);
                atomicLong.set(System.currentTimeMillis());
            }

            log.info("sendFlag:{} sampleFlag:{}, atomicBoolean：{}",
                    sendFlag,
                    DataConfigManager.getInstance().isSampleFlag(),
                    atomicBoolean.get());
            if (sendFlag && DataConfigManager.getInstance().isSampleFlag()) {
                messageSendService.batchSendMsg2Kafka("kaugnche", deviceDataVOS);
                atomicBoolean.set(false);
                atomicLong.set(0L);
            }

        } else {
            log.info("{}暂无有效重量数据", LocalDateTime.now());
        }
    }

    *//**
     * 执行系统命令（Java 8 兼容）
     *//*
    private void execSystemCommand(String cmd) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(cmd.split("\\s+"));
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String errMsg = errReader.readLine();
            errReader.close();
            throw new RuntimeException("命令执行失败: " + cmd + " | 错误信息: " + errMsg);
        }
        // 关闭进程流，避免资源泄漏
        process.getInputStream().close();
        process.getOutputStream().close();
        process.getErrorStream().close();
    }

    *//**
     * 销毁：应用关闭时释放资源
     *//*
    @PreDestroy
    public void destroy() {
        // 停止解析线程
        if (parseThread != null && parseThread.isAlive()) {
            parseThread.interrupt();
        }
        // 销毁candump进程
        if (candumpProcess != null) {
            candumpProcess.destroy();
        }
        // 关闭读取器
        if (candumpReader != null) {
            try {
                candumpReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // 关闭CAN0
        try {
            execSystemCommand("sudo ip link set " + CAN_CHANNEL + " down");
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("CAN总线资源已释放，服务退出");
    }
    */
}