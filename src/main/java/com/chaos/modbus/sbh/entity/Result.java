package com.chaos.modbus.sbh.entity;

import lombok.Data;

@Data
public class Result {
    public Result(){}
    public Result(String devName, Integer slaveId, String isOnline, String value) {
        this.devName = devName;
        this.slaveId = slaveId;
        this.isOnline = isOnline;
        this.value = value;
    }


    private String devName;
    private Integer slaveId;
    private String isOnline;
    private String value;
}
