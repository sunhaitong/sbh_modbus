package com.chaos.mine.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/14 11:07
 * @Version 1.0
 */
@Data
public class TboxSignalData {
    // 系统状态
    private boolean tboxReady = false;
    private long systemUptime = 0;

    // 信号数据
    private Map<String, Object> signals = new ConcurrentHashMap<>();

    // 故障信息
    private Map<String, Object> faults = new ConcurrentHashMap<>();

    // 时间戳
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastUpdateTime = new Date();

    // 系统信息
    private String configFileName = "";
    private String[] signalNames = new String[0];

    /**
     * 更新信号值
     */
    public void updateSignal(String signalName, Object value) {
        signals.put(signalName, value);
        this.lastUpdateTime = new Date();
    }

    /**
     * 更新故障信息
     */
    public void updateFault(String faultName, Object value) {
        faults.put(faultName, value);
        this.lastUpdateTime = new Date();
    }

    /**
     * 获取格式化信号数据（用于前端显示）
     */
    public Map<String, Object> getFormattedSignals() {
        Map<String, Object> formatted = new ConcurrentHashMap<>();

        // 系统状态
        formatted.put("systemStatus", tboxReady ? "正常运行" : "初始化中");
        formatted.put("systemUptime", formatUptime(systemUptime));
        formatted.put("lastUpdate", lastUpdateTime);

        // 关键信号分组
        Map<String, Object> engineSignals = new ConcurrentHashMap<>();
        Map<String, Object> vehicleSignals = new ConcurrentHashMap<>();
        Map<String, Object> systemSignals = new ConcurrentHashMap<>();
        Map<String, Object> warningSignals = new ConcurrentHashMap<>();

        // 分类处理信号
        signals.forEach((key, value) -> {
            String formattedValue = formatSignalValue(key, value);

            if (key.startsWith("Eng_")) {
                engineSignals.put(getSignalDisplayName(key), formattedValue);
            } else if (key.startsWith("Veh_") || key.startsWith("Fuel_") || key.startsWith("Brake_")) {
                vehicleSignals.put(getSignalDisplayName(key), formattedValue);
            } else if (key.contains("Warn") || key.contains("Fault") || key.contains("Low")) {
                warningSignals.put(getSignalDisplayName(key), formattedValue);
            } else {
                systemSignals.put(getSignalDisplayName(key), formattedValue);
            }
        });

        formatted.put("engineData", engineSignals);
        formatted.put("vehicleData", vehicleSignals);
        formatted.put("systemData", systemSignals);
        formatted.put("warningData", warningSignals);
        formatted.put("faultCodes", faults);

        return formatted;
    }

    private String formatUptime(long uptime) {
        long seconds = uptime / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private String formatSignalValue(String signalName, Object value) {
        if (value instanceof Double) {
            double doubleValue = (Double) value;

            switch (signalName) {
                case "Veh_Spd":
                    return String.format("%.1f km/h", doubleValue);
                case "Eng_Spd":
                    return String.format("%.0f RPM", doubleValue);
                case "Batt_Volt":
                    return String.format("%.1f V", doubleValue);
                case "Fuel_Level":
                    return String.format("%.1f %%", doubleValue);
                case "Eng_Oil_Press":
                    return String.format("%.1f kPa", doubleValue);
                case "Eng_Cool_Temp":
                case "Eng_In_Air_Temp":
                case "DEF_Temp":
                case "Hyd_Oil_Temp":
                case "Trans_Oil_Temp":
                    return String.format("%.1f °C", doubleValue);
                default:
                    return String.format("%.2f", doubleValue);
            }
        }
        return value.toString();
    }

    private String getSignalDisplayName(String signalName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("Veh_Spd", "车速");
        displayNames.put("Eng_Spd", "发动机转速");
        displayNames.put("Eng_Oil_Press", "机油压力");
        displayNames.put("Eng_Cool_Temp", "冷却液温度");
        displayNames.put("Eng_In_Air_Temp", "进气温度");
        displayNames.put("Batt_Volt", "电池电压");
        displayNames.put("Fuel_Level", "燃油液位");
        displayNames.put("Fuel_Total", "总燃油消耗");
        displayNames.put("DEF_Level", "尿素液位");
        displayNames.put("DEF_Temp", "尿素温度");
        displayNames.put("Brake_Press", "制动压力");
        displayNames.put("Hyd_Oil_Temp", "液压油温度");
        displayNames.put("Trans_Oil_Press", "变速箱油压");
        displayNames.put("Trans_Oil_Temp", "变速箱油温");
        displayNames.put("Cool_Level_Low", "冷却液低位警告");
        displayNames.put("AfterTreat_Warn", "尾气处理警告");
        displayNames.put("Eng_Fault", "发动机故障码");
        displayNames.put("DPF_Ash", "DPF灰分负载");
        displayNames.put("DPF_Regen", "DPF再生状态");
        displayNames.put("NOx_Out", "尾气NOx值");
        displayNames.put("NOx_In", "进气NOx值");

        return displayNames.getOrDefault(signalName, signalName);
    }
}