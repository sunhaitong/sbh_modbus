package com.chaos.modbus.sbh.entity;

import cn.hutool.core.annotation.Alias;
import lombok.Data;

/**
 * 设备mac地址--devId配置实体
 *
 * @author sunhaitong
 * @date 2021/8/24
 */
@Data
public class DeviceInfo {
    public DeviceInfo() {
    }

    /**
     * 顺序号
     */
    @Alias("序号")
    private Integer order;

    public DeviceInfo(Integer order, String devId, String pointNo, String kpIid) {
        this.order = order;
        this.devId = devId;
        this.pointNo = pointNo;
        this.kpIid = kpIid;
    }

    /**
     * 设备编码
     */
    @Alias("装置名称")
    private String devId;

    /**
     * 测点编号
     */
    @Alias("测点编号")
    private String pointNo;

    /**
     * 特征值
     */
    @Alias("描述")
    private String kpIid;
}
