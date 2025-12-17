package com.chaos.mine.entity;

import lombok.Data;

import java.util.List;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/12/2 15:08
 * @Version 1.0
 */

@Data
public class VehicleData {
    private double Eng_Oil_Press;
    private double Eng_Cool_Temp;
    private double Eng_In_Air_Temp;
    private double Eng_Op_Hrs;
    private double Eng_Spd;
    private double DPF_Regen;
    private double NOx_Out;
    private double NOx_In;
    private double DEF_Level;
    private double Fuel_Total;
    private double Veh_Spd;
    private double Trans_Oil_Press;
    private double Trans_Oil_Temp;
    private double Batt_Volt;
    private List<String> DM1;
}
