package com.chaos.mine.runner;

import com.fazecast.jSerialComm.SerialPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:32
 * @Version 1.0
 */

@Configuration
public class SerialConfig {

    @Value("${scale.serial.portName:COM1}")
    private String portName;

    @Bean
    public SerialPort serialPort() {
        SerialPort port = SerialPort.getCommPort(portName); // 修改为你的实际串口
        port.setBaudRate(9600);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        if (!port.openPort()) {
            throw new RuntimeException("无法打开串口！");
        }
        return port;
    }
}