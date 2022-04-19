package com.chaos.modbus.sbh.entity;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * @author sunht
 * @date 2021/11/1
 */
@Data
public class BaseEntity {

    @JSONField(name = "DevId")
    private String DevId;
    @JSONField(name = "Specialty")
    private String Specialty;
    @JSONField(name = "DataType")
    private String DataType;
    @JSONField(name = "ImplType")
    private String ImplType;
    @JSONField(name = "PointId")
    private String PointId;
    @JSONField(name = "DTime")
    private String DTime;
    @JSONField(name = "SN")
    private String SN;
}
