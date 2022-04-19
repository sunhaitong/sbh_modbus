package com.chaos.modbus.sbh.util;

import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.chaos.modbus.sbh.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author sunhaitong
 * @date 2021/8/23
 */

@Slf4j
public class JsonUtils {

    /**
     * 读取数据映射json文件
     *
     * @param filePath 资源目录相对路径
     * @return JSONObject
     */
    public static String getFromJsonFile(String filePath) {
        String result = "";
        try {
            //String src = "/usr/local/gateway/data_etl/" + filePath;
            /*String src = "/data1/gateway/data_etl" + filePath;
            File file = new File(src);*/
            String outpath = System.getProperty("user.dir") + File.separator + filePath;
            File file = new File(outpath);
            //ClassPathResource classPathResource = new ClassPathResource(filePath);
            result = IOUtils.toString(
                    new FileInputStream(file), Charsets.UTF_8.toString());
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return result;
    }

    /**
     * 读取excel文件
     *
     * @param filePath 资源目录相对路径
     * @return ArrayList
     */
    public static Map<Integer, DeviceInfo> getDevIdList(String filePath) {
        Map<Integer, DeviceInfo> deviceInfoMap = new HashMap<>();
        try {
            // String src = "/usr/local/gateway/data_etl/" + filePath;
            /*    String src = "/data1/gateway/data_etl" + filePath;
            File file = new File(src);*/
            // 获取jar目录下的配置文件
            String outpath = System.getProperty("user.dir") + File.separator + filePath;
            File file = new File(outpath);
            //ClassPathResource classPathResource = new ClassPathResource(filePath);

            //String outPath = System.getProperty("user.dir") + File.separator + filePath;
            // 获取文件流
            InputStream inputStream = new FileInputStream(file);
            ExcelReader reader = ExcelUtil.getReader(inputStream);
            int sheetCount = reader.getSheetCount();
            List<DeviceInfo> allRecords = new ArrayList<>(sheetCount);
            for (int i = 0; i < sheetCount; i++) {
                reader.setSheet(i);
                List<DeviceInfo> readAll = reader.readAll(DeviceInfo.class);
                allRecords.addAll(readAll);
            }
            for (DeviceInfo deviceInfo : allRecords) {
                if (!deviceInfoMap.containsKey(deviceInfo.getOrder())) {
                    deviceInfoMap.put(deviceInfo.getOrder(), deviceInfo);
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return deviceInfoMap;
    }
}
