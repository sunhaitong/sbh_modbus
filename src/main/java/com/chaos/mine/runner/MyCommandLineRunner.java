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

        // 初始化音量
        try {
            Process p1 = new ProcessBuilder(
                    "amixer", "-c", "0", "sset", "PCM", "100%"
            ).start();
            p1.waitFor();
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }
}
