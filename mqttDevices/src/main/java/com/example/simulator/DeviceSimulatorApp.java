package com.example.simulator;

import com.example.simulator.device.SimulatedDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DeviceSimulatorApp {

    private static final Logger logger = LoggerFactory.getLogger(DeviceSimulatorApp.class);
    // --- 配置区 ---
    private static final String BROKER_URL = "tcp://localhost:1883"; // 你的 EMQX 地址
    // --- 配置区结束 ---

    public static void main(String[] args) {
        logger.info("启动物联网设备模拟器...");

        // --- 定义要模拟的设备 (硬编码方式) ---
        List<SimulatedDevice> devices = new ArrayList<>();
        // 设备1: 温湿度传感器，10秒上报一次，初始状态为 ON
        devices.add(new SimulatedDevice("sensor-th-001", "TempHumSensor", BROKER_URL, 10, true));
        // 设备2: 智能开关，60秒上报一次状态（或仅变化时），初始状态为 OFF
        devices.add(new SimulatedDevice("switch-001", "SmartSwitch", BROKER_URL, 60, false));
        // 设备3: 另一个温湿度传感器，15秒上报一次，初始状态为 ON
        devices.add(new SimulatedDevice("sensor-th-002", "TempHumSensor", BROKER_URL, 15, true));

        // --- 启动设备模拟 ---
        // 为每个设备创建一个线程
        ExecutorService executorService = Executors.newFixedThreadPool(devices.size());
        for (SimulatedDevice device : devices) {
            executorService.submit(device); // 提交任务到线程池执行 run() 方法
        }

        logger.info("已提交 {} 个模拟设备到线程池运行...", devices.size());
        logger.info("模拟器正在运行。按 Ctrl+C 停止。");

        // --- 添加关闭钩子，用于优雅停止 ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，正在停止所有模拟设备...");
            for (SimulatedDevice device : devices) {
                device.stopSimulation(); // 请求设备停止
            }

            executorService.shutdown(); // 不再接受新任务
            try {
                // 等待最多 10 秒让现有任务完成或停止
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("模拟设备线程池未能在 10 秒内完全关闭，强制停止...");
                    executorService.shutdownNow(); // 强制停止
                }
            } catch (InterruptedException e) {
                logger.error("等待设备停止时被中断。", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("设备模拟器已关闭。");
        }));

        // 主线程可以退出，或者保持运行以执行其他管理任务 (如果需要)
        // 例如，可以监听控制台输入来动态控制设备
        // try {
        //     Thread.currentThread().join(); // 让主线程等待，直到被中断
        // } catch (InterruptedException e) {
        //     logger.info("主线程被中断。");
        // }
    }
}