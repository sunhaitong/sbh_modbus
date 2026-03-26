package com.chaos.mine;

import com.chaos.mine.entity.TboxSignalData;
import com.chaos.mine.runner.DataConfigManager;
import com.chaos.mine.service.ModbusService;
import com.chaos.mine.service.ModbusTcpService;
import com.chaos.mine.service.TboxDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/11 15:40
 * @Version 1.0
 */
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
public class DataController {

    @Autowired
    private ModbusService modbusService;

    @Autowired
    private ModbusTcpService modbusTcpService;

    @Autowired
    private TboxDataService tboxDataService;

    @GetMapping("/weight")
    public Map<String, Object> getWeights() {
        Map<String, Object> result = new HashMap<>();
        result.put("singleWeight", modbusService.getSingleWeight());
        result.put("totalWeight", modbusService.getTotalWeight());
        result.put("onlineStatus", DataConfigManager.getInstance().isOlineStatus());
        return result;
    }

    @GetMapping("/drilling")
    public Map<String, Object> getDrillin() {
        Map<String, Object> result = modbusTcpService.getTcpData();
        Map<String, Object> resultMap = new HashMap<>();

        StringBuilder satrtTimeBuilder = new StringBuilder();
        satrtTimeBuilder.append(result.get("R" + 42001) == null ? "2025" : result.get("R" + 42001)).append("年")
                .append(result.get("R" + 42002) == null ? "1"  : result.get("R" + 42002)).append("月")
                .append(result.get("R" + 42003) == null ? "1" : result.get("R" + 42003)).append("日")
                .append(result.get("R" + 42004) == null ? "1" : result.get("R" + 42004)).append("时")
                .append(result.get("R" + 42005) == null ? "1" : result.get("R" + 42005)).append("分")
                .append(result.get("R" + 42006) == null ? "0" : result.get("R" + 42006)).append("秒");

        StringBuilder endTimeBuilder = new StringBuilder();
        endTimeBuilder.append(result.get("R" + 42006) == null ? "2025" : result.get("R" + 42006)).append("年")
                .append(result.get("R" + 42007) == null ? "10" : result.get("R" + 42007)).append("月")
                .append(result.get("R" + 42008) == null ? "10" : result.get("R" + 42008)).append("日")
                .append(result.get("R" + 42009) == null ? "10" : result.get("R" + 42009)).append("时")
                .append(result.get("R" + 42010) == null ? "0" : result.get("R" + 42010)).append("分")
                .append(result.get("R" + 42011) == null ? "0" : result.get("R" + 42011)).append("秒");
        String workStartTime = satrtTimeBuilder.toString();
        String workEndTime = endTimeBuilder.toString();
        double drillingDepth = result.get("R" + 42013) == null ? 0d : (double) result.get("R" + 42013);
        double drillingCount = result.get("R" + 42014) == null ? 0d : (double) result.get("R" + 42014);
        double drillingTotal = result.get("R" + 42015) == null ? 0d : (double) result.get("R" + 42015);
        double runningTimeTotal = result.get("R" + 42022) == null ? 0d : (double) result.get("R" + 42022);
        double runningDayTotal = result.get("R" + 42024) == null ? 0d : (double) result.get("R" + 42024);
        resultMap.put("workStartTime", workStartTime);
        resultMap.put("workEndTime", workEndTime);
        resultMap.put("drillingDepth", drillingDepth);
        resultMap.put("drillingCount", drillingCount);
        resultMap.put("drillingTotal", drillingTotal);
        resultMap.put("runningTimeTotal", runningTimeTotal);
        resultMap.put("runningDayTotal", runningDayTotal);
        return resultMap;
    }

    /**
     * 获取完整的TBOX数据（Map格式）
     */
    @GetMapping("/tbox")
    public Map<String, Object> getAllSignals() {
        return tboxDataService.getAllSignalsWithDefault();
    }
}
