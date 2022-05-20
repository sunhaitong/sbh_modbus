package com.chaos.modbus.sbh.util;

import com.alibaba.fastjson.JSON;
import com.chaos.modbus.sbh.entity.Result;
import com.chaos.modbus.sbh.entity.TestResult;
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

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
     * 氢气
     */
    public static ModbusMaster master;


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
            params.setHost("192.169.1.82");
            params.setPort(502);
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
    public static ModbusMaster getMaster(String host) throws ModbusInitException {

        if(null == master){
            IpParameters params = new IpParameters();
            params.setHost(host);
            params.setPort(502);
            // TCP 协议
            ModbusMaster ret = modbusFactory.createTcpMaster(params, true);
            ret.setTimeout(5000);
            ret.init();
            master = ret;
        }

        return master;
    }

    public static void closeMaster(){
        if(null != master){
            master.destroy();
        }
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

    public static TestResult batchRead8Input(String host) {
        TestResult result = new TestResult();
        ModbusMaster master = null;
        result.setHost(host);
        try {
            master = getMaster(host);
            if (null == master) {
                result.setIsMasterOnline(false);
                return result;
            } else {
                result.setIsMasterOnline(true);
            }

            /**
             * 油品1
             */
            boolean node1 = master.testSlaveNode(1);
            String isOnline = node1 ? "通" : "不通";
            if (node1) {
                Number v031 = readInputRegisters(master, 1, 0,  DataType.FOUR_BYTE_FLOAT_SWAPPED);
                Number v032 = readInputRegisters(master, 1, 1,  DataType.FOUR_BYTE_FLOAT_SWAPPED);
                result.addResult(new Result("油品1温度", 1, isOnline, v031.toString()));
                result.addResult(new Result("油品1油品", 1, isOnline, v032.toString()));
            } else {
                result.addResult(new Result("油品1", 1, isOnline, ""));
            }

            /**
             * 油品2
             */
            boolean node2 = master.testSlaveNode(2);
            String isOnline2 = node2 ? "通" : "不通";

            if (node2) {
                Number v031 = readInputRegisters(master, 2, 0,  DataType.FOUR_BYTE_FLOAT_SWAPPED);
                Number v032 = readInputRegisters(master, 2, 1,  DataType.FOUR_BYTE_FLOAT_SWAPPED);
                result.addResult(new Result("油品2温度", 2, isOnline2, v031.toString()));
                result.addResult(new Result("油品2油品", 2, isOnline2, v032.toString()));
            } else {
                result.addResult(new Result("油品2", 2, isOnline2, ""));
            }

            /**
             * 信号量1
             */
            boolean input1 = master.testSlaveNode(3);
            String isOnline3 = input1 ? "通" : "不通";
            if (input1) {
                Boolean v021 = readInputStatus(master, 3, 0);
                result.addResult(new Result("信号量1", 3, isOnline3, v021.toString()));
            } else {
                result.addResult(new Result("信号量1", 3, isOnline3, ""));
            }

            /**
             * 信号量
             */
            boolean input2 = master.testSlaveNode(4);
            String isOnline4 = input1 ? "通" : "不通";
            if (input2) {
                Boolean v021 = readInputStatus(master, 4, 0);
                result.addResult(new Result("信号量2", 4, isOnline4, v021.toString()));
            } else {
                result.addResult(new Result("信号量2", 4, isOnline4, ""));
            }

            /**
             * 信号量
             */
            boolean input3 = master.testSlaveNode(5);
            String isOnline5 = input1 ? "通" : "不通";
            if (input3) {
                Boolean v021 = readInputStatus(master, 5, 0);
                result.addResult(new Result("信号量3", 5, isOnline5, v021.toString()));
            } else {
                result.addResult(new Result("信号量3", 5, isOnline5, ""));
            }

        } catch (ModbusInitException e) {
            e.printStackTrace();
        } catch (ModbusTransportException e) {
            e.printStackTrace();
        } catch (ErrorResponseException e) {
            e.printStackTrace();
        } finally {
            if (null != master) {
                master.destroy();
            }
        }
        return result;
    }

    /**
     * 读取[04 Input Registers 3x]类型 模拟量数据
     *
     * @param slaveId
     *            slaveId
     * @param offset
     *            位置
     * @param dataType
     *            数据类型,来自com.serotonin.modbus4j.code.DataType
     * @return 返回结果
     * @throws ModbusTransportException
     *             异常
     * @throws ErrorResponseException
     *             异常
     * @throws ModbusInitException
     *             异常
     */
    public static Number readInputRegisters(ModbusMaster master,int slaveId, int offset, int dataType)
            throws ModbusTransportException, ErrorResponseException, ModbusInitException {
        // 04 Input Registers类型数据读取
        BaseLocator<Number> loc = BaseLocator.inputRegister(slaveId, offset, dataType);
        Number value = master.getValue(loc);
        return value;
    }

    /**
     * 读取[02 Input Status 1x]类型 开关数据
     * @param slaveId
     * @param offset
     * @return
             * @throws ModbusTransportException
     * @throws ErrorResponseException
     * @throws ModbusInitException
     */
    public static Boolean readInputStatus(ModbusMaster master, int slaveId, int offset)
            throws ModbusTransportException, ErrorResponseException, ModbusInitException {
        // 02 Input Status
        BaseLocator<Boolean> loc = BaseLocator.inputStatus(slaveId, offset);
        Boolean value = master.getValue(loc);
        return value;
    }


    public static void scheduleTest() {
        List<String> hostList = new ArrayList<>();
        hostList.add("192.168.1.80");
        hostList.add("192.168.1.81");
        hostList.add("192.168.1.82");
        hostList.add("192.168.1.83");
        hostList.add("192.168.1.84");
        hostList.add("192.168.1.85");
        hostList.add("192.168.1.86");
        List<TestResult> results = new ArrayList<>();
        for (String host : hostList) {
            results.add(batchRead8Input(host));
        }
        SimpleDateFormat sdf = new SimpleDateFormat("MM-ddHH:mm:ss");
        String fileName = "result-" + sdf.format(new Date( System.currentTimeMillis()));
        File f = new File("D:\\test\\" + fileName + ".txt");
        FileOutputStream fos1= null;
        try {
            fos1 = new FileOutputStream(f);
            OutputStreamWriter dos1=new OutputStreamWriter(fos1);
            dos1.write(JSON.toJSONString(results));
            dos1.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
