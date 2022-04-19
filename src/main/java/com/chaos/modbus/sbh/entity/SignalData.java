package com.chaos.modbus.sbh.entity;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 遥信开关量
 *
 * @author sunht
 * @date 2021/11/1
 */
@Data
public class SignalData extends BaseEntity {
    public SignalData() {
        setDataType("D");
        setSpecialty("Z");
        setImplType("N");
        setSN("");
    }
    @JSONField(name = "KpiId")
    private String KpiId;

    @JSONField(name = "Value")
    private Integer Value;

    @JSONField(name = "HasSignal")
    private Integer HasSignal;

    @JSONField(name = "Step")
    private Integer Step = 240;

    @JSONField(name = "State")
    private Integer State = 1;
}
