package com.chaos.mine.runner;

import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Component
public class SerialPortManager {

    @Autowired
    private SerialConfig serialConfig;

    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final AtomicInteger referenceCount = new AtomicInteger(0);
    private volatile boolean initialized = false;

    // 流包装类，防止意外关闭
    private static class NonCloseableInputStream extends InputStream {
        private final InputStream delegate;

        public NonCloseableInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() {
            // 禁止关闭
            log.debug("尝试关闭输入流被阻止");
        }
    }

    private static class NonCloseableOutputStream extends OutputStream {
        private final OutputStream delegate;

        public NonCloseableOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() {
            // 禁止关闭
            log.debug("尝试关闭输出流被阻止");
        }
    }

    /**
     * 获取输入流（自动增加引用计数）
     */
    public InputStream getInputStream() {
        rwLock.readLock().lock();
        try {
            ensureInitialized();
            if (inputStream == null) {
                throw new IllegalStateException("串口未初始化");
            }
            referenceCount.incrementAndGet();
            log.debug("获取输入流，当前引用计数: {}", referenceCount.get());
            return new NonCloseableInputStream(inputStream);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取输出流（自动增加引用计数）
     */
    public OutputStream getOutputStream() {
        rwLock.readLock().lock();
        try {
            ensureInitialized();
            if (outputStream == null) {
                throw new IllegalStateException("串口未初始化");
            }
            referenceCount.incrementAndGet();
            log.debug("获取输出流，当前引用计数: {}", referenceCount.get());
            return new NonCloseableOutputStream(outputStream);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 释放流资源（引用计数-1）
     */
    public void release() {
        rwLock.readLock().lock();
        try {
            if (referenceCount.get() > 0) {
                int remaining = referenceCount.decrementAndGet();
                log.debug("释放资源，剩余引用计数: {}", remaining);
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 确保串口已初始化
     */
    private void ensureInitialized() {
        if (!initialized) {
            rwLock.writeLock().lock();
            try {
                if (!initialized) {
                    initializeSerialPort();
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }

    /**
     * 初始化串口
     */
    private boolean initializeSerialPort() {
        try {
            log.info("正在初始化串口: {}", serialConfig.getPortName());
            serialPort = SerialPort.getCommPort(serialConfig.getPortName());

            if (serialPort == null) {
                log.error("串口 {} 不存在", serialConfig.getPortName());
                return false;
            }

            // 如果串口已打开，先关闭
            if (serialPort.isOpen()) {
                serialPort.closePort();
            }

            // 打开串口
            boolean opened = serialPort.openPort();
            if (!opened) {
                log.error("无法打开串口: {}", serialConfig.getPortName());
                return false;
            }
            log.info("串口已打开: {}", serialConfig.getPortName());

            // 设置串口参数
            serialPort.setComPortParameters(
                    serialConfig.getBaudRate(),
                    serialConfig.getDataBits(),
                    serialConfig.getStopBits(),
                    serialConfig.getParity()
            );

            // 设置读取超时
            serialPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    serialConfig.getReadTimeout(),
                    0
            );

            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
            initialized = true;
            referenceCount.set(0);
            log.info("串口初始化成功: {}", serialConfig.getPortName());

        } catch (Exception e) {
            log.error("初始化串口错误: {}", e.getMessage(), e);
            cleanup();
        }
        return initialized;
    }

    /**
     * 确保串口可用
     */
    public boolean ensureSerialPortOpen() {
        rwLock.writeLock().lock();
        try {
            if (!initialized || serialPort == null || !serialPort.isOpen()) {
                log.warn("串口未打开，尝试重新打开...");
                initialized = false;
                return initializeSerialPort();
            }
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 清空输入缓冲区
     */
    public void clearInputStream() {
        rwLock.readLock().lock();
        try {
            if (inputStream != null && initialized) {
                while (inputStream.available() > 0) {
                    inputStream.read();
                }
            }
        } catch (IOException e) {
            log.error("清空输入缓冲区失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 检查串口是否打开
     */
    public boolean isOpen() {
        return initialized && serialPort != null && serialPort.isOpen();
    }

    /**
     * 重新配置串口参数
     */
    public boolean reconfigurePort(int baudRate, int dataBits, int stopBits, int parity) {
        rwLock.writeLock().lock();
        try {
            if (serialPort != null && serialPort.isOpen()) {
                serialPort.setComPortParameters(baudRate, dataBits, stopBits, parity);
                log.info("串口重新配置: 波特率={}, 数据位={}, 停止位={}, 校验={}",
                        baudRate, dataBits, stopBits, parity);
                return true;
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 清理资源（由Spring容器关闭时调用）
     */
    @PreDestroy
    public void cleanup() {
        rwLock.writeLock().lock();
        try {
            log.info("正在清理串口资源... 当前引用计数: {}", referenceCount.get());

            // 强制重置引用计数
            referenceCount.set(0);

            if (inputStream != null) {
                try {
                    inputStream.close();
                    log.info("输入流已关闭");
                } catch (IOException e) {
                    log.error("关闭输入流错误: {}", e.getMessage(), e);
                }
                inputStream = null;
            }

            if (outputStream != null) {
                try {
                    outputStream.close();
                    log.info("输出流已关闭");
                } catch (IOException e) {
                    log.error("关闭输出流错误: {}", e.getMessage(), e);
                }
                outputStream = null;
            }

            if (serialPort != null && serialPort.isOpen()) {
                boolean isClosed = serialPort.closePort();
                if (isClosed) {
                    log.info("串口已关闭");
                } else {
                    log.error("关闭串口失败");
                }
                serialPort = null;
            }

            initialized = false;
            log.info("串口资源清理完成");
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}