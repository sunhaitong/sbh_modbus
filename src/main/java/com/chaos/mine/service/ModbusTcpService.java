package com.chaos.mine.service;


import com.alibaba.fastjson.JSON;
import com.chaos.mine.entity.DeviceDataVO;
import com.chaos.mine.entity.DeviceInfo;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.util.KafkaUtils;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.locator.BaseLocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 17:06
 * @Version 1.0
 */

@Service
@Slf4j
public class ModbusTcpService {

    @Value("${modbustcp.host:192.168.1.6}")
    private String host;
    @Value("${modbustcp.port:502}")
    private int port;

    @Autowired
    private MessageSendService messageSendService;

    private ModbusMaster master;
    private final Map<String, Object> resultMap = new HashMap<>();

    private final AtomicReference<Map<String, Object>> tcpData = new AtomicReference<>(new HashMap<>());
    private Map<Integer, DeviceInfo> hydrogenDevInfoMap;
    @PostConstruct
    public void init() {
        try {
            // TCP 参数
            IpParameters params = new IpParameters();
            params.setHost(host);
            params.setPort(port);

            ModbusFactory factory = new ModbusFactory();
            master = factory.createTcpMaster(params, true);
            master.init();
            hydrogenDevInfoMap = DataConfigManager.getInstance().getZuanjingDeviceInfoMap();

        } catch (ModbusInitException e) {
            e.printStackTrace();
        }
    }

    public synchronized void readTcpData() {
        if (master == null || hydrogenDevInfoMap == null || hydrogenDevInfoMap.isEmpty()) return;

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DeviceInfo> entry : hydrogenDevInfoMap.entrySet()) {
            int address = entry.getKey();
            String type = entry.getValue().getType().toLowerCase(Locale.ROOT);
            try {
                Object value = readRegisterValue(address, type);
                result.put("R" + address, value);
                log.info("R" + address + "=" + value);
                messageSendService.sendMsg2Kafka("R" + address, (double) value);
            } catch (Exception e) {
                result.put("R" + address, "ERR");
            }
        }

        tcpData.set(result);
    }

    private Object readRegisterValue(int address, String type) throws ModbusTransportException, ErrorResponseException {
        int slaveId = 1; // 从站地址
        BaseLocator<?> locator;
        switch (type) {
            case "int16":
                locator = BaseLocator.holdingRegister(slaveId, address, DataType.TWO_BYTE_INT_SIGNED);
                break;
            case "uint16":
                locator = BaseLocator.holdingRegister(slaveId, address, DataType.TWO_BYTE_INT_UNSIGNED);
                break;
            case "int32":
                locator = BaseLocator.holdingRegister(slaveId, address, DataType.FOUR_BYTE_INT_SIGNED);
                break;
            case "uint32":
                locator = BaseLocator.holdingRegister(slaveId, address, DataType.FOUR_BYTE_INT_UNSIGNED);
                break;
            case "float32":
                locator = BaseLocator.holdingRegister(slaveId, address, DataType.FOUR_BYTE_FLOAT);
                break;
            default:
                locator = BaseLocator.holdingRegister(slaveId, address, DataType.TWO_BYTE_INT_SIGNED);
        }
        return master.getValue(locator);
    }

    public Map<String, Object> getTcpData() {
        return tcpData.get();
    }
}
