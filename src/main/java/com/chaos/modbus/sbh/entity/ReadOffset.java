package com.chaos.modbus.sbh.entity;

import lombok.Data;

/**
 * @author sunht
 * @date 2022/1/26
 */
@Data
public class ReadOffset {
    public ReadOffset(Integer start, Integer end) {
        this.start = start;
        this.end = end;
    }

    private Integer start;
    private Integer end;
}
