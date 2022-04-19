package com.chaos.modbus.sbh.entity;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * @author sunht
 * @date 2021/11/1
 */
@Data
public class SingleFeatureData extends BaseEntity {
    public SingleFeatureData() {
        setDataType("D");
        setSpecialty("Z");
        setImplType("N");
        setSN("");
    }
    @JSONField(name = "KpiId")
    private String KpiId;

    @JSONField(name = "Value")
    private Double Value;

    @JSONField(name = "HasSignal")
    private Integer HasSignal;

    @JSONField(name = "Step")
    private Integer Step = 240;

    @JSONField(name = "State")
    private Integer State = 1;
}
