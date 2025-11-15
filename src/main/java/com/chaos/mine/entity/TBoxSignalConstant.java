package com.chaos.mine.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/14 14:02
 * @Version 1.0
 */

public class TBoxSignalConstant {

    // 全量信号映射：Key=协议信号名（如Eng_Oil_Press），Value=对应默认值
    public static final Map<String, Object> FULL_SIGNAL_DEFAULT_MAP;

    static {
        FULL_SIGNAL_DEFAULT_MAP = new HashMap<>();
        // 1. 发动机相关信号（数值型默认0.0/0，布尔型默认false，数组默认空数组）
        FULL_SIGNAL_DEFAULT_MAP.put("Eng_Oil_Press", 0.0);          // 发动机机油压力（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Eng_Cool_Temp", 0.0);          // 发动机温度（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Eng_In_Air_Temp", 0.0);        // 发动机进气歧管温度（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Eng_Op_Hrs", 0.0);             // 发动机运行时间（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Eng_Spd", 0);                  // 发动机转速（int）
        FULL_SIGNAL_DEFAULT_MAP.put("Eng_Fault", new int[0]);       // 发动机故障报警码（int数组）
        FULL_SIGNAL_DEFAULT_MAP.put("Cool_Level_Low", false);       // 发动机冷却液液位低报警（boolean）
        FULL_SIGNAL_DEFAULT_MAP.put("AfterTreat_Warn", false);      // 发动机尾气处理报警（boolean）
        // 2. 柴油相关信号
        FULL_SIGNAL_DEFAULT_MAP.put("DEF_Temp", 0.0);               // 柴油机温度（double）
        FULL_SIGNAL_DEFAULT_MAP.put("DPF_Ash", 0.0);                // 柴油颗粒过滤器灰分负载水平（double）
        FULL_SIGNAL_DEFAULT_MAP.put("DPF_Regen", 0);                // 柴油颗粒过滤器再生状态（int）
        FULL_SIGNAL_DEFAULT_MAP.put("NOx_Out", 0.0);                // 后处理氮氧化物值（double）
        FULL_SIGNAL_DEFAULT_MAP.put("NOx_In", 0.0);                 // 预处理氮氧化物值（double）
        FULL_SIGNAL_DEFAULT_MAP.put("DEF_Level", 0.0);              // 发动机尿素液位（double）
        // 3. 燃油/车速相关信号
        FULL_SIGNAL_DEFAULT_MAP.put("Fuel_Total", 0.0);             // 发动机总燃油消耗量（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Fuel_Level", 0.0);             // 发动机燃油箱液位（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Veh_Spd", 0.0);                // 运动速度（double）
        // 4. 制动/液压相关信号
        FULL_SIGNAL_DEFAULT_MAP.put("Brake_Press", 0.0);            // 制动管路压力（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Hyd_Oil_Temp", 0.0);           // 液压油温度（double）
        // 5. 变速箱相关信号
        FULL_SIGNAL_DEFAULT_MAP.put("Trans_Oil_Press", 0.0);        // 变速箱油压（double）
        FULL_SIGNAL_DEFAULT_MAP.put("Trans_Oil_Temp", 0.0);         // 变速箱油温（double）
        // 6. 其他信号
        FULL_SIGNAL_DEFAULT_MAP.put("Batt_Volt", 0.0);              // 蓄电池电压（double）
        FULL_SIGNAL_DEFAULT_MAP.put("DM1", new int[0]);             // 故障码集合（int数组）
    }

    // 获取全量信号的默认Map（每次返回新对象，避免并发修改）
    public static Map<String, Object> getDefaultSignalMap() {
        return new HashMap<>(FULL_SIGNAL_DEFAULT_MAP);
    }
}
