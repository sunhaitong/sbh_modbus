package com.chaos.mine.offline;

import lombok.AllArgsConstructor;
import lombok.Data;


/**
 * @ClassName sunht
 * @Description TODO
 * @date 2025/11/25 16:51
 * @Version 1.0
 */
@Data
@AllArgsConstructor
public class OfflineMsg {
    private Long id;
    private String topic;
    private String msg;
}
