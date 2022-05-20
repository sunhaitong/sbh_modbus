package com.chaos.modbus.sbh.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * @author sunht
 * @date 2021/8/31
 */

@Slf4j
@Component
public class MyCommandLineRunner implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        // DataConfigManager.getInstance().loadDataStandardConfig();
    }
}
