package com.chaos.mine.runner;

import com.fazecast.jSerialComm.SerialPort;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:32
 * @Version 1.0
 */

@Data
@Component
public class SerialConfig {

    @Value("${scale.serial.portName:COM1}")
    private String portName;
    private int baudRate = 9600;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = 0; // 0:无校验, 1:奇校验, 2:偶校验
    private int readTimeout = 1000;
    private int frameTimeout = 50;
}