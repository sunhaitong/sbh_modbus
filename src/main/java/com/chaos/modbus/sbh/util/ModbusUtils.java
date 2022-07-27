package com.chaos.modbus.sbh.util;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.locator.BaseLocator;
import lombok.extern.slf4j.Slf4j;

/**
 * modbus通讯工具类,采用modbus4j实现
 *
 * @author sunht
 * @since 2022-01-25
 *
 */
@Slf4j
public class ModbusUtils {
    /**
     * 工厂。
     */
    static ModbusFactory modbusFactory;
    static {
        if (modbusFactory == null) {
            modbusFactory = new ModbusFactory();
        }
    }

    /**
     * 南瑞
     */
    public static ModbusMaster nanruiMaster;

    /**
     * 测温
     */
    public static ModbusMaster temperatureMaster;

    /**
     * 氢气
     */
    public static ModbusMaster hydrogenMaster;


    /**
     * 获取master
     *
     * @return
     * @throws ModbusInitException
     */
    public static ModbusMaster getMasterForNanRui() throws ModbusInitException {

        if(null == nanruiMaster){
            IpParameters params = new IpParameters();
            params.setHost("192.169.1.223");
            params.setPort(9035);
            // TCP 协议
            ModbusMaster master = modbusFactory.createTcpMaster(params, true);
            master.setTimeout(5000);


            master.init();
            nanruiMaster = master;
        }


        return nanruiMaster;
    }


    /**
     * 获取master
     *
     * @return
     * @throws ModbusInitException
     */
    public static ModbusMaster getHydrogenMaster() throws ModbusInitException {

        if(null == hydrogenMaster){
            IpParameters params = new IpParameters();
            params.setHost("192.169.1.224");
            params.setPort(9035);
            // TCP 协议
            ModbusMaster master = modbusFactory.createTcpMaster(params, true);
            master.setTimeout(5000);
            master.init();
            hydrogenMaster = master;
        }

        return hydrogenMaster;
    }


    /**
     * 获取master
     *
     * @return
     * @throws ModbusInitException
     */
    public static ModbusMaster getTemperatureMaster() throws ModbusInitException {

        if(null == temperatureMaster){
            IpParameters params = new IpParameters();
            params.setHost("192.169.1.222");
            params.setPort(9035);
            // TCP 协议
            ModbusMaster master = modbusFactory.createTcpMaster(params, true);
            master.setTimeout(5000);


            master.init();
            temperatureMaster = master;
        }


        return temperatureMaster;
    }
/*
    *//**
     * 获取master
     *
     * @return
     * @throws ModbusInitException
     *//*
    public static ModbusMaster getMaster(String host) throws ModbusInitException {
        log.info("master is null. get host:{}", host);
        IpParameters params = new IpParameters();
        params.setHost(host);
        params.setPort(9035);
        // TCP 协议
        ModbusMaster master = modbusFactory.createTcpMaster(params, true);
        master.setTimeout(3000);
        master.init();
        return master;
    }*/

    /**
     * 批量读取
     * @param host
     * @param slaveId
     * @param start
     * @param end
     * @param dataType
     * @return
     * @throws ModbusInitException
     * @throws ErrorResponseException
     * @throws ModbusTransportException
     */
    public static BatchResults<Integer> batchReadInput(String host, int slaveId, int start, int end, int dataType) throws ModbusInitException, ErrorResponseException, ModbusTransportException {
        log.info("read data start:{}, end:{}", start, end);
        BatchRead<Integer> batch = new BatchRead<Integer>();
        boolean isLast = end - start < 99;
        for (int i = start; i <= end; i++) {
            if (i == end && isLast) {
                break;
            }
            if (dataType == DataType.FOUR_BYTE_FLOAT) {
                batch.addLocator(i,BaseLocator.holdingRegister(slaveId, i * 2, dataType));
            } else {
                batch.addLocator(i,BaseLocator.holdingRegister(slaveId, i, dataType));
            }

        }
        ModbusMaster master = null;
        if (host.equals("192.169.1.223")) {
            master = getMasterForNanRui();
        } else if (host.equals("192.169.1.222")) {
            master = getTemperatureMaster();
        } else if (host.equals("192.169.1.224")) {
            master = getHydrogenMaster();
        }
        if (null == master) {
            return new BatchResults<>();
        }
        BatchResults<Integer> result = master.send(batch);
        return result;
    }

    public static Boolean readCoilStatus(int slaveId, int offset, String host)
            throws ModbusTransportException, ErrorResponseException, ModbusInitException {
        // 01 Coil Status
        BaseLocator<Boolean> loc = BaseLocator.coilStatus(slaveId, offset);
        ModbusMaster master = getMasterForNanRui();
        Boolean value = master.getValue(loc);
        return value;
    }


    /**
     * 读取03类型数据
     * @param host
     * @param slaveId
     * @param offset
     * @param dataType
     * @return
     * @throws ModbusTransportException
     * @throws ErrorResponseException
     * @throws ModbusInitException
     */
    public static Number readHoldingRegister(String host, int slaveId, int offset, int dataType)
            throws ModbusTransportException, ErrorResponseException, ModbusInitException {
        BaseLocator<Number> loc = BaseLocator.holdingRegister(slaveId, offset, dataType);
        ModbusMaster master = getHydrogenMaster();
        Number value = master.getValue(loc);
        return value;
    }

    public static void main(String[] args) throws ModbusInitException, ModbusTransportException, ErrorResponseException {
        BaseLocator<Number> loc = BaseLocator.holdingRegister(1, 0, DataType.TWO_BYTE_INT_SIGNED);
        IpParameters params = new IpParameters();
        params.setHost("127.0.0.1");
        params.setPort(502);
        // TCP 协议
        ModbusMaster master = modbusFactory.createTcpMaster(params, true);
        master.setTimeout(5000);
        master.init();
        Number value = master.getValue(loc);
        log.info("===value{}",Double.valueOf(value.toString()));

    }
}
