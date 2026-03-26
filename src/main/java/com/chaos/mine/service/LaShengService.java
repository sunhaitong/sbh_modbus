package com.chaos.mine.service;

import com.chaos.mine.runner.SerialConfig;
import com.chaos.mine.runner.SerialPortManager;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Slf4j
@Service
public class LaShengService {

    @Autowired
    private SerialPortManager serialPortManager;

    @Autowired
    private SerialConfig serialConfig;

    private static final int SLAVE_ID = 2;
    private ModbusMaster master;
    private SerialPortWrapperImpl wrapper;

    @PostConstruct
    public void init() throws Exception {
        // 确保串口可用
        serialPortManager.ensureSerialPortOpen();

        // 创建SerialPortWrapper包装器
        wrapper = new SerialPortWrapperImpl(
                serialConfig.getPortName(),
                serialConfig.getBaudRate(),
                serialConfig.getDataBits(),
                serialConfig.getStopBits(),
                serialConfig.getParity()
        );

        ModbusFactory factory = new ModbusFactory();
        master = factory.createRtuMaster(wrapper);
        master.setTimeout(serialConfig.getReadTimeout());
        master.init();

        log.info("Modbus RTU 串口已连接: {}", serialConfig.getPortName());
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

    @PreDestroy
    public void destroy() {
        if (master != null) {
            master.destroy();
        }
    }
}