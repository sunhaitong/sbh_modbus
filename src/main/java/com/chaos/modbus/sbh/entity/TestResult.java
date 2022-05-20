package com.chaos.modbus.sbh.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TestResult {
    private String host;
    private Boolean isMasterOnline;
    private List<Result> results = new ArrayList<>();
    public void addResult(Result result) {
        this.results.add(result);
    }
}
