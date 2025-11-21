package com.chaos.mine.service;

import com.chaos.mine.util.CRC16Util;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:36
 * @Version 1.0
 */
@Slf4j
@Service
public class ModbusService {

    @Autowired
    private MessageSendService messageSendService;

    private final SerialPort serialPort;
    private final AtomicReference<Double> singleWeight = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalWeight = new AtomicReference<>(0.0);

    public ModbusService(SerialPort serialPort) {
        this.serialPort = serialPort;
    }

    public synchronized void readWeights() {
        try {
            // 读单铲重量 0x01 0x03 0x00 0x00 0x00 0x02 + CRC
            byte[] cmd1 = new byte[]{0x01, 0x03, 0x00, 0x00, 0x00, 0x02};
            byte[] crc1 = CRC16Util.getCRC(cmd1);
            byte[] request1 = ByteBuffer.allocate(cmd1.length + 2).put(cmd1).put(crc1).array();
            byte[] resp1 = sendAndReceive(request1);
            if (resp1 != null && resp1.length >= 9) {
                int val = ((resp1[3] & 0xFF) << 24) | ((resp1[4] & 0xFF) << 16)
                        | ((resp1[5] & 0xFF) << 8) | (resp1[6] & 0xFF);
                singleWeight.set((double) val); // 单位是kg
                log.info("Single weight: {}", (double) val);
                messageSendService.sendMsg2Kafka("singleWeight", (double) val);
            }



            // 读累计重量 0x01 0x03 0x00 0x02 0x00 0x02 + CRC
            byte[] cmd2 = new byte[]{0x01, 0x03, 0x00, 0x02, 0x00, 0x02};
            byte[] crc2 = CRC16Util.getCRC(cmd2);
            byte[] request2 = ByteBuffer.allocate(cmd2.length + 2).put(cmd2).put(crc2).array();
            byte[] resp2 = sendAndReceive(request2);
            if (resp2 != null && resp2.length >= 9) {
                int val = ((resp2[3] & 0xFF) << 24) | ((resp2[4] & 0xFF) << 16)
                        | ((resp2[5] & 0xFF) << 8) | (resp2[6] & 0xFF);
                totalWeight.set((double) val);
                log.info("Total weight: {}", (double) val);
                messageSendService.sendMsg2Kafka("totalWeight", (double) val);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] sendAndReceive(byte[] request) throws Exception {
        OutputStream out = serialPort.getOutputStream();
        InputStream in = serialPort.getInputStream();
        out.write(request);
        out.flush();

        Thread.sleep(100); // 等待响应

        byte[] buffer = new byte[64];
        int len = in.read(buffer);
        if (len > 0) {
            byte[] resp = new byte[len];
            System.arraycopy(buffer, 0, resp, 0, len);
            return resp;
        }
        return null;
    }

    public double getSingleWeight() {
        return singleWeight.get();
    }

    public double getTotalWeight() {
        return totalWeight.get();
    }
}
