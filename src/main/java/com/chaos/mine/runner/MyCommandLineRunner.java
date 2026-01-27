package com.chaos.mine.runner;

import com.chaos.mine.service.CanWeightReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * @author sunht
 * @date 2021/8/31
 */

@Slf4j
@Component
public class MyCommandLineRunner implements CommandLineRunner {
    @Autowired
    private CanWeightReader canWeightReader;

    @Override
    public void run(String... args) throws Exception {
        DataConfigManager.getInstance().loadDataStandardConfig();
        canWeightReader.read();
    }
}
