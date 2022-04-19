package com.chaos.modbus.sbh.runner;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author sunht
 * @date 2022/1/21
 */
@Component
public class Test {
    public static void main(String[] args) {
        int t = 1689;

        for (int i = 0; i <= t; i++) {
            if (i % 100 == 0 && (t-i) > 100){
                System.out.println(i + "," + (i+ 99));
            }
        }
        if (t % 100 != 0){
            System.out.println( (t/100) * 100 + " :" + (((t/100) * 100) + (t % 100)));
        }
        List<Integer> list = new ArrayList<Integer>(){{
            add(new Integer(1));
            add(new Integer(2));
            add(new Integer(3));
        }};
        System.out.println(list.toString());
    }
}
