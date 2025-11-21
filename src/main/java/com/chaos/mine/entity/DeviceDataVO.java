package com.chaos.mine.entity;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * @author sunht
 * @date 2023/7/8
 */
@Data
public class DeviceDataVO {
    /**
     * 设备编号
     */
    private String equipNum;

    /**
     * 测点编号
     */
    private String pointNum;

    /**
     * 指标参数
     */
    private String paramNum;

    /**
     * 时间
     */
    private long sampleTime;
    private long recvTime;

    /**
     * 值
     */
    private Double value;

    private String kpiId;

    @JSONField(serialize = false,deserialize = false)
    public String getTag(){
        return equipNum+"_"+pointNum+"_"+paramNum;
    }
}
