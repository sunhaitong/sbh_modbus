package com.chaos.modbus.sbh.entity;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * @author sunht
 * @date 2021/11/1
 */
@Data
public class MultiFeatureData extends BaseEntity {
    public MultiFeatureData() {
        setDataType("DMA");
        setSpecialty("Z");
        setImplType("N");
        setSN("");
    }
    @JSONField(name = "Values")
    private List<ValueObject> values;

    @JSONField(name = "Step")
    private Integer Step = 240;

    @JSONField(name = "State")
    private Integer State = 1;
}
