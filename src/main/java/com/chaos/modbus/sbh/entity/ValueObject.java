package com.chaos.modbus.sbh.entity;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * @author sunht
 * @date 2021/11/1
 */
@Data
public class ValueObject {
    public ValueObject() {
    }

    @JSONField(name = "KpiId")
    private String KpiId;

    @JSONField(name = "Value")
    private Double Value;

    public ValueObject(String kpiId, Double value) {
        KpiId = kpiId;
        Value = value;
    }
}
