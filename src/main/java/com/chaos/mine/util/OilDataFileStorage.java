package com.chaos.mine.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@Component
@Slf4j
public class OilDataFileStorage {

    private static final String OIL_FILE = "/data/sql/oil_level.txt";

    /**
     * 保存油量
     */
    public void saveOilLevel(double oilLevel) {
        try (FileWriter fw = new FileWriter(OIL_FILE)) {
            fw.write(String.valueOf(oilLevel));
        } catch (IOException e) {
            log.error("保存油量失败", e);
        }
    }

    /**
     * 获取油量
     */
    public Double getOilLevel() {
        File file = new File(OIL_FILE);
        if (!file.exists()) {
            return null;
        }
        try {
            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
            return Double.parseDouble(content);
        } catch (IOException e) {
            log.error("读取油量失败", e);
            return null;
        }
    }
}