package com.chaos.mine.util;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

public class WavPlayer {

    public static void playWav(String filePath) {
        try {
            // 1. 创建File对象
            File audioFile = new File(filePath);

            // 2. 获取音频输入流
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);

            // 3. 获取音频格式
            AudioFormat format = audioStream.getFormat();

            // 4. 获取数据行信息
            DataLine.Info info = new DataLine.Info(Clip.class, format);

            // 5. 获取并打开Clip对象
            Clip audioClip = (Clip) AudioSystem.getLine(info);
            audioClip.open(audioStream);

            // 6. 播放音频
            audioClip.start();

            // 等待播放完成
            Thread.sleep((long) ((audioClip.getMicrosecondLength() + 2000) / 1000));

            // 7. 关闭资源
            audioClip.close();
            audioStream.close();

        } catch (UnsupportedAudioFileException | IOException |
                 LineUnavailableException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String wavFilePath = "D:\\video\\done48.wav";
        playWav(wavFilePath);
    }
}
