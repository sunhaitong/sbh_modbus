package com.chaos.mine.service;

import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.MineCartWeighTool;
import com.sun.jna.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class CanWeightReader {

    // ===== 常量定义 =====
    private static final int PF_CAN = 29;
    private static final int AF_CAN = PF_CAN;
    private static final int SOCK_RAW = 3;
    private static final int CAN_RAW = 1;

    private static final int SIOCGIFINDEX = 0x8933;
    private static final int WEIGHT_CAN_ID = 0x600;

    // 原子变量存储最新重量（线程安全）
    private final AtomicReference<Double> latestWeightTons = new AtomicReference<>(null);

    private final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    private final AtomicLong atomicLong = new AtomicLong(0L);

    @Value("${equip.no:test}")
    private String equipNo;

    @Autowired
    private MessageSendService messageSendService;

    // ===== libc 映射 =====
    public interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int socket(int domain, int type, int protocol);
        int ioctl(int fd, int request, ifreq ifr);
        int bind(int sockfd, sockaddr_can addr, int addrlen);
        int read(int fd, byte[] buffer, int count);
        int close(int fd);
    }

    // ===== struct ifreq =====
    public static class ifreq extends Structure {
        public byte[] ifr_name = new byte[16];
        public int ifr_ifindex;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("ifr_name", "ifr_ifindex");
        }
    }

    // ===== struct sockaddr_can =====
    public static class sockaddr_can extends Structure {
        public short can_family;
        public int can_ifindex;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("can_family", "can_ifindex");
        }
    }

    public void read() {

        int fd = LibC.INSTANCE.socket(PF_CAN, SOCK_RAW, CAN_RAW);
        if (fd < 0) {
            throw new RuntimeException("socket() failed");
        }

        // 绑定 can0
        ifreq ifr = new ifreq();
        byte[] name = "can0".getBytes();
        System.arraycopy(name, 0, ifr.ifr_name, 0, name.length);

        if (LibC.INSTANCE.ioctl(fd, SIOCGIFINDEX, ifr) < 0) {
            throw new RuntimeException("ioctl(SIOCGIFINDEX) failed");
        }

        sockaddr_can addr = new sockaddr_can();
        addr.can_family = (short) AF_CAN;
        addr.can_ifindex = ifr.ifr_ifindex;

        if (LibC.INSTANCE.bind(fd, addr, addr.size()) < 0) {
            throw new RuntimeException("bind() failed");
        }

        System.out.println("CAN ready, waiting for data...");

        byte[] buffer = new byte[16]; // sizeof(struct can_frame)

        while (true) {
            int n = LibC.INSTANCE.read(fd, buffer, buffer.length);
            if (n <= 0) {
                continue;
            }

            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

            // ===== struct can_frame 正确解析 =====
            int canId = bb.getInt(); // can_id (0~3)
            bb.get();                // can_dlc (4)

            // padding: __pad, __res0, __res1
            bb.get(); // 5
            bb.get(); // 6
            bb.get(); // 7

            byte[] data = new byte[8];
            bb.get(data); // 8~15

            if ((canId & 0x7FF) == WEIGHT_CAN_ID) {
                double tons = parseWeightTons(data);
                latestWeightTons.set(tons);
                System.out.printf("重量: %.3f t%n", tons);
            }
        }
    }

    // ===== WST 重量解析 =====
    // Byte0-2: 3字节，小端，单位 0.1kg，24bit 补码
    private static double parseWeightTons(byte[] d) {
        int raw = (d[0] & 0xFF)
                | ((d[1] & 0xFF) << 8)
                | ((d[2] & 0xFF) << 16);

        // 24bit 有符号数
        if ((raw & 0x800000) != 0) {
            raw -= (1 << 24);
        }

        double kg = raw * 0.1;
        return kg / 1000.0;
    }

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
}
