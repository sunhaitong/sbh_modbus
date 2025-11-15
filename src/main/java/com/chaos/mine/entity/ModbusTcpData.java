package com.chaos.mine.entity;

import cn.hutool.core.annotation.Alias;
import lombok.Data;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:26
 * @Version 1.0
 */
@Data
public class ModbusTcpData {
    @Alias("地址")
    private int address;

    @Alias("数据类型")
    private String type;

    @Alias("数据")
    private String dec;
}
