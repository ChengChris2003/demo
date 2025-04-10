package com.example.simulator.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulatedDevice implements MqttCallback, Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedDevice.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    private final String deviceId;
    private final String deviceType;
    private final String brokerUrl;
    private final long reportIntervalSeconds;

    private final String reportTopic;
    private final String commandTopic;
    private final String statusTopic; // 用于主动上报在线/离线状态

    private MqttClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reportingTaskFuture = null;
    private final AtomicBoolean isOn = new AtomicBoolean(true); // 设备“电源”状态
    private final AtomicBoolean connected = new AtomicBoolean(false); // MQTT 连接状态
    private volatile boolean stopRequested = false; // 外部请求停止标志

    public SimulatedDevice(String deviceId, String deviceType, String brokerUrl, long reportIntervalSeconds, boolean initiallyOn) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.brokerUrl = brokerUrl;
        this.reportIntervalSeconds = reportIntervalSeconds;
        this.isOn.set(initiallyOn);

        // 定义主题
        this.reportTopic = String.format("device/report/%s", deviceId);
        this.commandTopic = String.format("device/command/%s", deviceId); // 和后端 MqttService 交互的命令主题
        this.statusTopic = String.format("device/status/%s", deviceId); // 可选的状态主题
        logger.info("设备 [{}] 配置完成: 类型={}, 上报间隔={}s, 初始状态={}",
                deviceId, deviceType, reportIntervalSeconds, isOn.get() ? "ON" : "OFF");
    }

    @Override
    public void run() {
        logger.info("模拟设备 [{}] 线程启动...", deviceId);
        while (!stopRequested) {
            if (!connected.get()) {
                try {
                    connect();
                } catch (MqttException e) {
                    logger.error("设备 [{}] 连接失败，将在 10 秒后重试...", deviceId);
                    try {
                        TimeUnit.SECONDS.sleep(10); // 连接失败后等待一段时间重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        logger.warn("设备 [{}] 等待重连时被中断，停止模拟。", deviceId);
                        stopRequested = true; // 退出循环
                    }
                }
            } else {
                // 已连接，保持运行或等待
                try {
                    TimeUnit.SECONDS.sleep(5); // 短暂休眠，避免空转 CPU
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("设备 [{}] 休眠时被中断，停止模拟。", deviceId);
                    stopRequested = true;
                }
            }
        }
        // 清理资源
        shutdown();
        logger.info("模拟设备 [{}] 线程结束。", deviceId);
    }

    private void connect() throws MqttException {
        String clientId = "sim-" + deviceId + "-" + System.currentTimeMillis(); // 时间戳确保唯一性
        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false); // 我们手动处理重连逻辑
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(20);

        // 设置遗嘱消息 (LWT)
        Map<String, Object> offlinePayload = new HashMap<>();
        offlinePayload.put("deviceId", deviceId);
        offlinePayload.put("status", "offline"); // 设备异常掉线时的状态
        offlinePayload.put("timestamp", System.currentTimeMillis());
        try {
            String offlineMsg = objectMapper.writeValueAsString(offlinePayload);
            options.setWill(statusTopic, offlineMsg.getBytes(), 1, false); // QoS 1, 非保留
            logger.info("设备 [{}] 设置 LWT 成功: Topic={}", deviceId, statusTopic);
        } catch (Exception e) {
            logger.error("设备 [{}] 设置 LWT 失败: {}", deviceId, e.getMessage());
        }

        client.setCallback(this); // 设置回调
        logger.info("设备 [{}] 正在连接到 {}...", deviceId, brokerUrl);
        client.connect(options);
        connected.set(true); // 标记为已连接
        logger.info("设备 [{}] 连接成功！ClientID: {}", deviceId, client.getClientId());

        // 重新订阅主题 (重连后必须重新订阅)
        client.subscribe(commandTopic, 1);
        logger.info("设备 [{}] 订阅命令主题: {}", deviceId, commandTopic);

        // 发布当前状态
        publishStatus(isOn.get() ? "online" : "offline");

        // 如果设备是开启状态，启动数据上报
        if (isOn.get()) {
            startReportingTask();
        }
    }

    // 启动定时上报任务
    private void startReportingTask() {
        // 先取消已有的任务（如果有）
        stopReportingTask(false); // false表示不关闭调度器本身

        if (reportIntervalSeconds > 0 && !"SmartSwitch".equalsIgnoreCase(deviceType)) { // 只有传感器类型且间隔大于0才上报
            reportingTaskFuture = scheduler.scheduleAtFixedRate(this::publishData,
                    2, // 延迟 2 秒开始
                    reportIntervalSeconds,
                    TimeUnit.SECONDS);
            logger.info("设备 [{}] 已启动数据上报，间隔 {} 秒", deviceId, reportIntervalSeconds);
        } else if ("SmartSwitch".equalsIgnoreCase(deviceType)){
            logger.info("设备 [{}] 是开关类型，仅在状态变化时上报状态，不定期上报数据。", deviceId);
            publishStatus(isOn.get() ? "online" : "offline"); // 确保当前状态已上报
        }
        else {
            logger.info("设备 [{}] 上报间隔为 0 或类型不支持，不启动定期数据上报", deviceId);
        }
    }

    // 停止定时上报任务
    private void stopReportingTask(boolean shutdownScheduler) {
        if (reportingTaskFuture != null) {
            reportingTaskFuture.cancel(false); // false - 允许当前任务完成
            reportingTaskFuture = null;
            logger.info("设备 [{}] 已停止数据上报任务", deviceId);
        }
        if (shutdownScheduler && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    // 发布模拟数据
    private void publishData() {
        if (!isOn.get() || !connected.get()) {
            logger.trace("设备 [{}] 处于关闭或断开状态，跳过数据上报", deviceId);
            return;
        }

        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("deviceId", deviceId);
            payloadMap.put("timestamp", System.currentTimeMillis());

            // 根据设备类型生成数据
            if ("TempHumSensor".equalsIgnoreCase(deviceType)) {
                double temp = 15.0 + random.nextDouble() * 15.0; // 15-30
                double humidity = 40.0 + random.nextDouble() * 30.0; // 40-70
                payloadMap.put("temperature", String.format("%.1f", temp));
                payloadMap.put("humidity", String.format("%.1f", humidity));
            } else {
                // 其他类型设备可以只上报在线状态或不上报周期性数据
                payloadMap.put("status", "online"); // 简单上报在线
            }


            String jsonPayload = objectMapper.writeValueAsString(payloadMap);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(1);
            message.setRetained(false); // 数据通常不保留

            client.publish(reportTopic, message);
            logger.info("设备 [{}] 上报数据: {}", deviceId, jsonPayload);

        } catch (MqttException e) {
            logger.error("设备 [{}] 上报数据时 MQTT 出错: {}", deviceId, e.getMessage());
            // 如果是连接丢失导致，connectionLost 回调会处理
        } catch (Exception e) {
            logger.error("设备 [{}] 准备或上报数据时出错: {}", deviceId, e.getMessage(), e);
        }
    }

    // 发布设备状态 (online/offline)
    private void publishStatus(String status) {
        if (!connected.get()) return; // 未连接则不发布
        try {
            Map<String, Object> statusPayload = new HashMap<>();
            statusPayload.put("deviceId", deviceId);
            statusPayload.put("status", status);
            statusPayload.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(statusPayload);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(1);
            message.setRetained(true); // 状态消息设为保留，新订阅者能立即知道状态

            client.publish(statusTopic, message);
            logger.info("设备 [{}] 发布状态 '{}' 到 {}", deviceId, status, statusTopic);
        } catch (Exception e) {
            logger.error("设备 [{}] 发布状态 '{}' 失败: {}", deviceId, status, e.getMessage());
        }
    }

    // --- MqttCallback ---
    @Override
    public void connectionLost(Throwable cause) {
        logger.warn("设备 [{}] 连接丢失: {}", deviceId, cause.getMessage());
        connected.set(false); // 更新连接状态
        stopReportingTask(false); // 连接丢失时停止上报
        // run() 方法中的循环会检测到 connected=false 并尝试重连
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());
        logger.info("设备 [{}] 收到命令: Topic='{}', Payload='{}'", deviceId, topic, payload);

        try {
            // 简单处理 ON/OFF 命令
            if ("ON".equalsIgnoreCase(payload)) {
                if (isOn.compareAndSet(false, true)) { // 状态从未开启 -> 开启
                    logger.info("设备 [{}] 已被远程开启", deviceId);
                    publishStatus("online");
                    if (connected.get()) { // 只有连接时才启动上报
                        startReportingTask();
                    }
                } else {
                    logger.info("设备 [{}] 收到 ON 命令，但已处于开启状态", deviceId);
                }
            } else if ("OFF".equalsIgnoreCase(payload)) {
                if (isOn.compareAndSet(true, false)) { // 状态从开启 -> 未开启
                    logger.info("设备 [{}] 已被远程关闭", deviceId);
                    stopReportingTask(false); // 关闭时停止上报
                    publishStatus("offline");
                } else {
                    logger.info("设备 [{}] 收到 OFF 命令，但已处于关闭状态", deviceId);
                }
            } else {
                logger.warn("设备 [{}] 收到未知命令: {}", deviceId, payload);
            }
        } catch (Exception e) {
            logger.error("设备 [{}] 处理命令时出错: {}", deviceId, e.getMessage(), e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 可选：记录消息送达，对于QoS 1和2
        // logger.trace("设备 [{}] 消息送达: {}", deviceId, token.getMessageId());
    }

    // 外部调用的停止方法
    public void stopSimulation() {
        this.stopRequested = true;
        // 可以尝试中断线程来更快响应停止信号
        // (需要获取到运行此实例的线程对象)
    }

    // 内部清理方法
    private void shutdown() {
        logger.info("正在关闭设备 [{}]...", deviceId);
        stopReportingTask(true); // true 表示要关闭 scheduler
        if (client != null && client.isConnected()) {
            try {
                // 发布最后一次 offline 状态 (尽力而为)
                publishStatus("offline");
                // 等待短暂时间让消息发出
                try { TimeUnit.MILLISECONDS.sleep(200); } catch (InterruptedException ignored) {}
                client.disconnect();
                logger.info("设备 [{}] MQTT 连接已断开。", deviceId);
            } catch (MqttException e) {
                logger.error("设备 [{}] 断开 MQTT 连接时出错: {}", deviceId, e.getMessage());
            }
        }
        if (client != null) {
            try {
                client.close(); // 释放资源
                logger.info("设备 [{}] MQTT 客户端已关闭。", deviceId);
            } catch (MqttException e) {
                logger.error("设备 [{}] 关闭 MQTT 客户端时出错: {}", deviceId, e.getMessage());
            }
        }
        connected.set(false);
    }
}