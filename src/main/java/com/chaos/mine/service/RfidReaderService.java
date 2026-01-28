package com.chaos.mine.service;

import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.runner.DataConfigManager;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class RfidReaderService {

    @Value("${rfid.serial.port.name:COM4}")
    private String portName;
    private static final int BAUD_RATE = 115200;
    private static final byte ADDR_MSB = 0x00;
    private static final byte ADDR_LSB = 0x00;

    private SerialPort serialPort;

    private boolean isPortOpen = false;


    @Value("${equip.no:test}")
    private String equipNo;

    @Autowired
    private MessageSendService messageSendService;

    /**
     * 初始化串口
     */
    @PostConstruct
    public void init() {
        try {
            serialPort = SerialPort.getCommPort(portName);
            serialPort.setBaudRate(BAUD_RATE);
            serialPort.setNumDataBits(8);
            serialPort.setNumStopBits(1);
            serialPort.setParity(SerialPort.NO_PARITY);
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

            isPortOpen = serialPort.openPort();
            if (isPortOpen) {
                log.info("RFID串口 {} 打开成功", portName);
            } else {
                log.error("RFID串口 {} 打开失败", portName);
            }
        } catch (Exception e) {
            log.error("初始化RFID串口失败: {}", e.getMessage());
        }
    }

    /**
     * 构建主动盘存一次指令
     */
    /*private byte[] buildInventoryOnce() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.put((byte) 'R');
        buffer.put((byte) 'F');
        buffer.put((byte) 0x00);
        buffer.put(ADDR_MSB);
        buffer.put(ADDR_LSB);
        buffer.put((byte) 0x22); // 主动盘存一次
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x00);

        byte[] data = new byte[7];
        buffer.position(0);
        buffer.get(data, 0, 7);
        byte checksum = calculateChecksum(data);

        ByteBuffer frame = ByteBuffer.allocate(8);
        frame.put(data);
        frame.put(checksum);

        return frame.array();
    }*/
    private byte[] buildInventoryOnce() {
        byte[] frame = new byte[9];
        frame[0] = 'R';
        frame[1] = 'F';
        frame[2] = 0x00;
        frame[3] = ADDR_MSB;
        frame[4] = ADDR_LSB;
        frame[5] = 0x22;
        frame[6] = 0x00;
        frame[7] = 0x00;
        frame[8] = checksum(Arrays.copyOf(frame, 8));
        return frame;
    }

    private byte checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += b & 0xFF;
        }
        return (byte) ((~sum + 1) & 0xFF);
    }

    /**
     * 解析EPC数据
     */
    private String parseEPC(byte[] frame) {
        if (frame.length < 10 || frame[0] != 'R' || frame[1] != 'F') {
            return null;
        }

        int paramLen = ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        if (8 + paramLen > frame.length) {
            return null;
        }

        byte[] params = new byte[paramLen];
        System.arraycopy(frame, 8, params, 0, paramLen);

        int i = 0;
        while (i + 1 < params.length) {
            int t = params[i] & 0xFF;
            int l = params[i + 1] & 0xFF;

            if (t == 0x50) { // Tag TLV
                byte[] tag = new byte[l];
                System.arraycopy(params, i + 2, tag, 0, l);

                int j = 0;
                while (j + 1 < tag.length) {
                    int tt = tag[j] & 0xFF;
                    int ll = tag[j + 1] & 0xFF;

                    if (tt == 0x01) { // EPC
                        byte[] epcBytes = new byte[ll];
                        System.arraycopy(tag, j + 2, epcBytes, 0, ll);
                        return bytesToHex(epcBytes).toUpperCase();
                    }
                    j += 2 + ll;
                }
            }
            i += 2 + l;
        }
        return null;
    }

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 周期读取RFID任务 - 每200ms执行一次
     */
    public void readRfidTask() {
        if (!isPortOpen) {
            return;
        }

        try {
            // 发送读取指令
            byte[] command = buildInventoryOnce();
            int bytesWritten = serialPort.writeBytes(command, command.length);
            if (bytesWritten != command.length) {
                log.warn("RFID指令发送不完整");
                return;
            }

            // 等待并读取响应
            Thread.sleep(50); // 等待设备响应
            byte[] readBuffer = new byte[256];
            int bytesRead = serialPort.readBytes(readBuffer, readBuffer.length);

            if (bytesRead > 0) {
                byte[] response = new byte[bytesRead];
                System.arraycopy(readBuffer, 0, response, 0, bytesRead);

                String epc = parseEPC(response);
                if (epc != null && !epc.isEmpty()) {
                    log.info("读取到RFID EPC: {}", epc);
                    // 这里可以添加业务逻辑，比如保存到数据库、发送消息等
                    processRfidData(epc);
                }
            }
        } catch (Exception e) {
            log.error("读取RFID时发生错误: {}", e.getMessage());
        }
    }

    /**
     * 处理读取到的RFID数据
     */
    private void processRfidData(String epc) {
        List<DeviceDataVO> deviceDataVOS = new ArrayList<>();
        DeviceDataVO rfid = new DeviceDataVO();
        rfid.setEquipNum(equipNo);
        rfid.setPointNum("01");
        rfid.setParamNum("rfid");
        rfid.setValue(Double.parseDouble(epc));
        rfid.setSampleTime(System.currentTimeMillis());
        rfid.setRecvTime(System.currentTimeMillis());
        deviceDataVOS.add(rfid);

        log.info("rfid kafka data:{}", JSON.toJSONString(deviceDataVOS));
        if (DataConfigManager.getInstance().isSampleFlag()) {
            log.info("send rfid to kafka");
            messageSendService.batchSendMsg2Kafka("rfid", deviceDataVOS);
        } else {
            log.info("speed < 200  send rfid to kafka pass.");
        }
    }

    /**
     * 关闭串口
     */
    @PreDestroy
    public void cleanup() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            log.info("RFID串口已关闭");
        }
    }
}