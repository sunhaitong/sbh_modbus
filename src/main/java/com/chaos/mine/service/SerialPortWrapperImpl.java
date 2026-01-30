package com.chaos.mine.service;

import com.fazecast.jSerialComm.SerialPort;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.io.InputStream;
import java.io.OutputStream;

public class SerialPortWrapperImpl implements SerialPortWrapper {

    private final SerialPort serialPort;

    public SerialPortWrapperImpl(String port, int baud, int dataBits, int stopBits, int parity) {
        serialPort = SerialPort.getCommPort(port);
        serialPort.setComPortParameters(baud, dataBits, stopBits, parity);
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_BLOCKING,
                1000,
                1000
        );
    }

    @Override
    public void open() {
        serialPort.openPort();
    }

    @Override
    public void close() {
        serialPort.closePort();
    }

    @Override
    public InputStream getInputStream() {
        return serialPort.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        return serialPort.getOutputStream();
    }

    @Override
    public int getBaudRate() {
        return serialPort.getBaudRate();
    }

    @Override
    public int getFlowControlIn() {
        return 0;
    }

    @Override
    public int getFlowControlOut() {
        return 0;
    }

    @Override
    public int getDataBits() {
        return 0;
    }

    @Override
    public int getStopBits() {
        return 0;
    }

    @Override
    public int getParity() {
        return 0;
    }
}
