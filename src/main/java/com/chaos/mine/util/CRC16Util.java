package com.chaos.mine.util;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:35
 * @Version 1.0
 */

public class CRC16Util {
    public static byte[] getCRC(byte[] data) {
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
        byte hi = (byte) ((crc & 0xFF00) >> 8);
        byte lo = (byte) (crc & 0x00FF);
        return new byte[]{lo, hi}; // 低位在前
    }
}
