package com.chaos.mine.service;

import com.chaos.mine.util.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

@Service
@Slf4j
public class HeartService {
    @Value("${kafka.url}")
    private String kafkaHost;

    @Value("${equip.no:test}")
    private String equipNo;

    /**
     * 每秒执行一次的定时任务
     */
    @Scheduled(fixedRate = 1000)
    @Async// 1000毫秒 = 1秒
    public void heartBeat() {
        try {

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if (address instanceof Inet4Address) {
                        String ip = address.getHostAddress();

                        KafkaUtils.send(kafkaHost, "heartbeat", "equipNo:" + equipNo + " IP:" + ip );
                    }
                }
            }
        } catch (Exception e) {
           log.error(e.getMessage());
        }
    }
}