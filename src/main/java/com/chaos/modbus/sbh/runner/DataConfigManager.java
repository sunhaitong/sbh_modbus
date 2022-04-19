package com.chaos.modbus.sbh.runner;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.chaos.modbus.sbh.entity.DeviceInfo;
import com.chaos.modbus.sbh.util.JsonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据标准化配置管理类
 *
 * @author sunhaitong
 * @date 2021/8/23
 */

@Data
@Slf4j
public class DataConfigManager {
    private static DataConfigManager dataConfigManager = null;

    private DataConfigManager() {
    }

    /**
     * 遥信设备信息对照
     */
    private Map<Integer, DeviceInfo> signalDeviceInfoMap = new HashMap<>();

    /**
     * 遥信设备信息对照
     */
    private Map<Integer, DeviceInfo> measureDeviceInfoMap = new HashMap<>();

    /**
     * 温度设备信息对照
     */
    private Map<Integer, DeviceInfo> temperatureDeviceInfoMap = new HashMap<>();

    /**
     * 氢气
     */
    private Map<Integer, DeviceInfo> hydrogenDeviceInfoMap = new HashMap<>();

    /**
     * 原始数据topic
     */
    private String originTopic;

    /**
     * 特征数据topic
     */
    private String kpiTopic;

    /**
     * kafka服务url
     */
    private String kafkaUrl;

    /**
     * 上报周期
     */
    private Integer reportCycle = 0;

    /**
     * 参数映射配置文件路径
     */
    private static final String DATA_MAPPING_PATH = "config/data-mapping.json";

    /**
     * kafka配置
     */
    private static final String KAFKA_CONFIG = "config/kafka-config.json";

    /**
     * 遥信设备信息对照表
     */
    private static final String SIGNAL_DEVICES_PATH = "config/remote-signal.xls";

    /**
     * 遥测设备信息对照表
     */
    private static final String MEASURE_DETAIL_PATH = "config/remote-measure.xls";


    private static final String TEMPERATURE_DETAIL_PATH = "config/wireless-temperature.xls";


    /**
     * 获取映射管理类实例
     *
     * @return 映射管理类实例
     */
    public static DataConfigManager getInstance() {
        if (dataConfigManager == null) {
            dataConfigManager = new DataConfigManager();
        }
        return dataConfigManager;
    }

    /**
     * 加载数据标准化相关配置
     */
    public void loadDataStandardConfig() {
        log.info("*******************init data mapping ********************");

        // 加载devId
        loadDeviceDetail();

        //  加载kafka配置
        loadKafkaConfig();

    }



    /**
     * 加载设备信息配置
     */
    private void loadDeviceDetail() {
        temperatureDeviceInfoMap = JsonUtils.getDevIdList(TEMPERATURE_DETAIL_PATH);
        signalDeviceInfoMap = JsonUtils.getDevIdList(SIGNAL_DEVICES_PATH);
        measureDeviceInfoMap = JsonUtils.getDevIdList(MEASURE_DETAIL_PATH);
        hydrogenDeviceInfoMap = loadHydrogenDeviceInfoMap();
    }

    private Map<Integer, DeviceInfo> loadHydrogenDeviceInfoMap() {
        Map<Integer, DeviceInfo> result = new HashMap<>();
        result.put(2, new DeviceInfo(2, "824192E01","02", "油中平均溶解氢值"));
        result.put(13, new DeviceInfo(13, "824192E01","02", "油中溶解氢日变化率"));
        result.put(15, new DeviceInfo(15, "824192E01","02", "油中溶解氢周变化率"));
        return result;
    }


    private void loadKafkaConfig(){
        String kafkaConfig = JsonUtils.getFromJsonFile(KAFKA_CONFIG);
        JSONObject jsonObject = JSON.parseObject(kafkaConfig);
        if (jsonObject != null) {
            originTopic = jsonObject.getString("topic_origin");
            kpiTopic = jsonObject.getString("topic_kpi");
            kafkaUrl = jsonObject.getString("url");
            reportCycle = jsonObject.getInteger("report_cycle");
            log.info("kafka config :{}", jsonObject.toJSONString());
        } else {
            log.info("get kafka topic is null.");
        }

    }
}
