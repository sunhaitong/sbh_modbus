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
    private int Eng_Oil_Press;
    private int Eng_Cool_Temp;
    private int Eng_In_Air_Temp;
    private double Eng_Op_Hrs;
    private double Eng_Spd;
    private int DPF_Regen;
    private int NOx_Out;
    private int NOx_In;
    private int DEF_Level;
    private int Fuel_Total;
    private int Veh_Spd;
    private int Trans_Oil_Press;
    private int Trans_Oil_Temp;
    private double Batt_Volt;
    private List<String> DM1;
}
