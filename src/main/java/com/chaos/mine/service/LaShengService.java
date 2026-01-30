package com.chaos.mine.service;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
public class LaShengService {


    @Value("${scale.serial.portName:COM1}")
    private String portName;
    private static final int SLAVE_ID = 2;

    private ModbusMaster master;

    @PostConstruct
    public void init() throws Exception {

        SerialPortWrapper wrapper = new SerialPortWrapperImpl(
                portName,
                9600,
                8,
                1,
                0   // 0 = NONE 校验
        );

        ModbusFactory factory = new ModbusFactory();
        master = factory.createRtuMaster(wrapper);
        master.init();

        System.out.println("Modbus RTU 串口已连接");
    }

    public short[] readHoldingRegisters(int start, int count) throws Exception {
        ReadHoldingRegistersRequest req =
                new ReadHoldingRegistersRequest(SLAVE_ID, start, count);

        ReadHoldingRegistersResponse res =
                (ReadHoldingRegistersResponse) master.send(req);

        if (res.isException()) {
            throw new RuntimeException(res.getExceptionMessage());
        }
        return res.getShortData();
    }
}
