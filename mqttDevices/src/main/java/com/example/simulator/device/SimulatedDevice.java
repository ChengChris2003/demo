package com.example.simulator.device; // 确保包名正确

import com.fasterxml.jackson.databind.ObjectMapper; // 确保导入
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
    // 使用静态 ObjectMapper 避免重复创建，但注意线程安全（默认是线程安全的）
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    // 设备固有属性
    private final String deviceId;
    private final String deviceType;
    private final String brokerUrl;
    private final long reportIntervalSeconds;

    // MQTT 主题
    private final String reportTopic;    // 用于上报业务数据 (e.g., 温湿度)
    private final String commandTopic;   // 用于接收控制命令 (e.g., ON/OFF)
    private final String statusTopic;    // 用于上报在线/离线状态 (retained)
    private final String registerTopic;  // 用于设备首次上线或重连后注册 (retained)
    private final String unregisterTopic;// (可选) 用于设备正常下线通知

    // MQTT 客户端和状态
    private MqttClient client;
    private final AtomicBoolean isOn; // 设备逻辑上的电源状态 (ON/OFF)
    private final AtomicBoolean connected = new AtomicBoolean(false); // MQTT 连接状态
    private volatile boolean stopRequested = false; // 外部请求停止模拟标志

    // 定时任务调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reportingTaskFuture = null; // 数据上报任务的 Future

    /**
     * 构造函数
     * @param deviceId 设备唯一ID
     * @param deviceType 设备类型 (e.g., "TempHumSensor", "SmartSwitch")
     * @param brokerUrl MQTT Broker 地址
     * @param reportIntervalSeconds 数据上报间隔（秒），<=0 表示不自动上报数据
     * @param initiallyOn 初始电源状态
     */
    public SimulatedDevice(String deviceId, String deviceType, String brokerUrl, long reportIntervalSeconds, boolean initiallyOn) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.brokerUrl = brokerUrl;
        this.reportIntervalSeconds = reportIntervalSeconds;
        this.isOn = new AtomicBoolean(initiallyOn);

        // 定义主题
        this.reportTopic = String.format("device/report/%s", deviceId);
        this.commandTopic = String.format("device/command/%s", deviceId);
        this.statusTopic = String.format("device/status/%s", deviceId);
        this.registerTopic = "device/lifecycle/register";      // 所有设备使用相同注册主题
        this.unregisterTopic = "device/lifecycle/unregister";  // 所有设备使用相同注销主题

        logger.info("设备 [{}] 配置完成: Type={}, Interval={}s, InitialState={}",
                deviceId, deviceType, reportIntervalSeconds, isOn.get() ? "ON" : "OFF");
    }

    /**
     * 模拟设备运行的主循环，负责保持连接和响应停止信号。
     */
    @Override
    public void run() {
        logger.info("模拟设备 [{}] 线程启动...", deviceId);
        while (!stopRequested) {
            if (!connected.get()) { // 如果未连接
                try {
                    connect(); // 尝试连接
                    // 连接成功后，connect() 方法内部会处理状态发布和任务启动
                } catch (MqttException e) {
                    logger.error("设备 [{}] 连接失败，将在 10 秒后重试...", deviceId);
                    safeSleep(10000); // 等待10秒
                    if(stopRequested) break; // 如果在等待期间被要求停止，则退出
                }
            } else { // 如果已连接
                safeSleep(5000); // 短暂休眠，避免空转
            }
        }
        // 循环结束，执行清理
        shutdown();
        logger.info("模拟设备 [{}] 线程结束。", deviceId);
    }

    /**
     * 建立 MQTT 连接，设置选项、回调、LWT，并订阅主题。
     * 连接成功后会发布注册信息和当前状态。
     * @throws MqttException 如果连接失败
     */
    private void connect() throws MqttException {
        String clientId = "sim-" + deviceId + "-" + System.currentTimeMillis(); // 保证唯一性
        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false); // 手动处理重连逻辑以便控制状态和任务
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(20); // 秒

        // --- 设置遗嘱消息 (Last Will and Testament - LWT) ---
        Map<String, Object> offlinePayload = new HashMap<>();
        offlinePayload.put("deviceId", deviceId);
        offlinePayload.put("status", "offline"); // 异常掉线时的状态
        offlinePayload.put("timestamp", System.currentTimeMillis());
        try {
            String offlineMsg = objectMapper.writeValueAsString(offlinePayload);
            // 遗嘱消息发布到状态主题，QoS=1，保留消息 (覆盖之前的在线状态)
            options.setWill(statusTopic, offlineMsg.getBytes(), 1, true);
            logger.info("设备 [{}] 设置 LWT 成功: Topic={}", deviceId, statusTopic);
        } catch (Exception e) {
            logger.error("设备 [{}] 设置 LWT 失败: {}", deviceId, e.getMessage());
        }
        // --- LWT 设置结束 ---

        client.setCallback(this); // 设置回调处理类
        logger.info("设备 [{}] 正在连接到 {}...", deviceId, brokerUrl);
        client.connect(options);
        connected.set(true); // 更新连接状态
        logger.info("设备 [{}] 连接成功！ClientID: {}", deviceId, client.getClientId());

        // *** 连接成功后发布注册信息 (Retained) ***
        publishRegistrationInfo();

        // *** 重新订阅命令主题 (重连后必须) ***
        client.subscribe(commandTopic, 1); // QoS 1
        logger.info("设备 [{}] 订阅命令主题: {}", deviceId, commandTopic);

        // *** 发布当前确切状态 (Retained) ***
        publishStatus(isOn.get() ? "online" : "offline");

        // 如果设备逻辑状态是 ON，则启动数据上报任务
        if (isOn.get()) {
            startReportingTask();
        }
    }

    /**
     * 启动周期性数据上报任务。
     * 如果任务已在运行，则不执行任何操作。
     * 只为非开关类型且上报间隔大于0的设备启动。
     */
    private void startReportingTask() {
        // 确保 MQTT 已连接且设备处于开启状态
        if (!connected.get() || !isOn.get()) {
            logger.debug("设备 [{}] 未连接或未开启，不启动上报任务", deviceId);
            return;
        }
        // 检查任务是否已经在运行
        if (reportingTaskFuture != null && !reportingTaskFuture.isDone()) {
            logger.debug("设备 [{}] 数据上报任务已在运行", deviceId);
            return;
        }
        // 只有非开关类型且间隔有效时才启动
        if (reportIntervalSeconds > 0 && !"SmartSwitch".equalsIgnoreCase(deviceType)) {
            reportingTaskFuture = scheduler.scheduleAtFixedRate(this::publishData,
                    2, // 延迟 2 秒开始第一次上报
                    reportIntervalSeconds,
                    TimeUnit.SECONDS);
            logger.info("设备 [{}] 已启动数据上报，间隔 {} 秒", deviceId, reportIntervalSeconds);
        } else if ("SmartSwitch".equalsIgnoreCase(deviceType)) {
            logger.info("设备 [{}] (开关) 不进行周期性数据上报，仅在状态变化时上报状态。", deviceId);
            // 可以在这里确保状态已经上报
            publishStatus(isOn.get() ? "online" : "offline");
        } else {
            logger.info("设备 [{}] 上报间隔为 0 或类型不支持，不启动定期数据上报", deviceId);
        }
    }

    /**
     * 停止周期性数据上报任务。
     * @param shutdownScheduler 是否同时关闭底层的调度器线程（通常在设备完全停止时才为 true）
     */
    private void stopReportingTask(boolean shutdownScheduler) {
        if (reportingTaskFuture != null) {
            boolean cancelled = reportingTaskFuture.cancel(false); // false: 允许当前任务完成
            if(cancelled) {
                logger.info("设备 [{}] 已停止数据上报任务", deviceId);
            }
            reportingTaskFuture = null;
        }
        // 如果需要关闭调度器 (通常在整个设备模拟结束时)
        if (shutdownScheduler && scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("设备 [{}] 的调度器未能在 5 秒内停止", deviceId);
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("设备 [{}] 的调度器已关闭", deviceId);
        }
    }

    /**
     * 发布设备的模拟业务数据到 reportTopic。
     */
    private void publishData() {
        // 再次检查状态，确保任务取消后不会意外发送
        if (!isOn.get() || !connected.get()) {
            logger.trace("设备 [{}] 处于关闭或断开状态，跳过此次数据上报", deviceId);
            // 如果任务仍在运行但状态不对，尝试取消任务
            if (reportingTaskFuture != null) stopReportingTask(false);
            return;
        }

        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("deviceId", deviceId);
            payloadMap.put("timestamp", System.currentTimeMillis());

            // 根据设备类型生成模拟数据
            if ("TempHumSensor".equalsIgnoreCase(deviceType)) {
                double temp = 15.0 + random.nextDouble() * 15.0; // 15-30
                double humidity = 40.0 + random.nextDouble() * 30.0; // 40-70
                payloadMap.put("temperature", String.format("%.1f", temp));
                payloadMap.put("humidity", String.format("%.1f", humidity));
            }
            // else if ("SmartSwitch".equalsIgnoreCase(deviceType)) {
            // 开关类型通常不在这里周期性上报状态，状态由 publishStatus 处理
            // }
            else {
                logger.trace("设备 [{}] 类型 '{}' 无特定数据上报逻辑", deviceId, deviceType);
                // 可以选择不上报任何额外数据
                return; // 或者直接返回，不发送空数据
            }

            String jsonPayload = objectMapper.writeValueAsString(payloadMap);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(1);
            message.setRetained(false); // 业务数据通常不保留

            client.publish(reportTopic, message);
            logger.info("设备 [{}] 上报数据到 {}: {}", deviceId, reportTopic, jsonPayload);

        } catch (MqttException e) {
            // 特别处理连接丢失导致的发布失败
            if (!client.isConnected()) {
                logger.warn("设备 [{}] 上报数据时发现连接已丢失", deviceId);
                connected.set(false); // 更新状态
                stopReportingTask(false); // 停止任务
                // run() 循环会处理重连
            } else {
                logger.error("设备 [{}] 上报数据时 MQTT 出错: {}", deviceId, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("设备 [{}] 准备或上报数据时出错: {}", deviceId, e.getMessage(), e);
        }
    }

    /**
     * 发布设备的在线/离线状态到 statusTopic (作为保留消息)。
     * @param status "online" 或 "offline"
     */
    private void publishStatus(String status) {
        if (!connected.get()) {
            logger.warn("设备 [{}] 无法发布状态 '{}' 到 {}，MQTT 未连接。", deviceId, status, statusTopic);
            return;
        }
        try {
            Map<String, Object> statusPayload = new HashMap<>();
            statusPayload.put("deviceId", deviceId);
            statusPayload.put("status", status.toLowerCase()); // 统一小写
            statusPayload.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(statusPayload);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(1);
            message.setRetained(true); // *** 状态消息必须是保留消息 ***

            client.publish(statusTopic, message);
            logger.info("设备 [{}] 发布状态 '{}' 到 {}", deviceId, status, statusTopic);
        } catch (Exception e) {
            logger.error("设备 [{}] 发布状态 '{}' 到 {} 失败: {}", deviceId, status, statusTopic, e.getMessage());
        }
    }

    /**
     * 发布设备的注册信息到 registerTopic (作为保留消息)。
     */
    private void publishRegistrationInfo() {
        if (!connected.get()) return;
        try {
            Map<String, Object> regInfo = new HashMap<>();
            regInfo.put("deviceId", this.deviceId);
            regInfo.put("deviceType", this.deviceType);
            regInfo.put("status", this.isOn.get() ? "online" : "offline"); // 报告当前状态
            regInfo.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(regInfo);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(1);
            message.setRetained(true); // *** 注册消息设为保留 ***

            client.publish(registerTopic, message);
            logger.info("设备 [{}] 发布注册/上线信息到 {}", deviceId, registerTopic);

        } catch (Exception e) {
            logger.error("设备 [{}] 发布注册信息失败: {}", deviceId, e.getMessage());
        }
    }

    /**
     * (可选) 发布设备注销/下线信息。
     */
    private void publishUnregisterInfo() {
        if (client == null || !client.isConnected()) return;
        try {
            Map<String, Object> unregInfo = new HashMap<>();
            unregInfo.put("deviceId", this.deviceId);
            unregInfo.put("status", "offline");
            unregInfo.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(unregInfo);
            MqttMessage message = new MqttMessage(jsonPayload.getBytes());
            message.setQos(1);
            // 注销信息通常不保留，或者发布一个空的保留消息到注册主题来清除
            message.setRetained(false);

            client.publish(unregisterTopic, message);
            logger.info("设备 [{}] 发布注销/下线信息到 {}", deviceId, unregisterTopic);

            // 可选：清除注册主题上的保留消息
            // client.publish(registerTopic, new byte[0], 1, true);

        } catch (Exception e) {
            logger.error("设备 [{}] 发布注销信息失败: {}", deviceId, e.getMessage());
        }
    }


    // --- MqttCallback 回调实现 ---
    @Override
    public void connectionLost(Throwable cause) {
        logger.warn("设备 [{}] 连接丢失: {}", deviceId, cause.getMessage());
        connected.set(false); // 更新状态
        stopReportingTask(false); // 停止上报任务
        // run() 方法中的循环会检测到连接状态并尝试重连
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());
        logger.info("设备 [{}] 收到命令: Topic='{}', Payload='{}'", deviceId, topic, payload);

        try {
            // 简单处理 ON/OFF 命令 (忽略大小写)
            if ("ON".equalsIgnoreCase(payload.trim())) {
                if (isOn.compareAndSet(false, true)) { // 只有当状态从未开启变为开启时才动作
                    logger.info("设备 [{}] 已被远程开启", deviceId);
                    publishStatus("online"); // 发布新状态
                    if (connected.get()) { // 只有连接时才启动上报
                        startReportingTask();
                    }
                } else {
                    logger.info("设备 [{}] 收到 ON 命令，但已处于开启状态", deviceId);
                }
            } else if ("OFF".equalsIgnoreCase(payload.trim())) {
                if (isOn.compareAndSet(true, false)) { // 只有当状态从开启变为未开启时才动作
                    logger.info("设备 [{}] 已被远程关闭", deviceId);
                    stopReportingTask(false); // 关闭时停止数据上报
                    publishStatus("offline"); // 发布新状态
                } else {
                    logger.info("设备 [{}] 收到 OFF 命令，但已处于关闭状态", deviceId);
                }
            } else {
                // 可以尝试解析 JSON 命令，例如 {"action": "SET_INTERVAL", "value": 5}
                try {
                    Map<String, Object> commandMap = objectMapper.readValue(payload, Map.class);
                    String action = (String) commandMap.get("action");
                    // ... 根据 action 处理更复杂的命令 ...
                    logger.warn("设备 [{}] 收到可解析但未处理的 JSON 命令: action={}", deviceId, action);
                } catch (Exception jsonException){
                    logger.warn("设备 [{}] 收到未知或无法解析的命令: {}", deviceId, payload);
                }
            }
        } catch (Exception e) {
            logger.error("设备 [{}] 处理命令时出错: {}", deviceId, e.getMessage(), e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1 或 2 的消息发送完成回调
        // logger.trace("设备 [{}] 消息送达确认: {}", deviceId, token.getMessageId());
    }

    /**
     * 外部调用此方法来请求停止设备模拟。
     */
    public void stopSimulation() {
        logger.info("请求停止设备 [{}]...", deviceId);
        this.stopRequested = true;
        // 可以考虑中断 run() 方法所在的线程以更快响应 (如果能获取到线程对象)
        // Thread associatedThread = ...;
        // if (associatedThread != null) associatedThread.interrupt();
    }

    /**
     * 关闭 MQTT 连接和调度器，释放资源。
     */
    private void shutdown() {
        logger.info("正在关闭设备 [{}] 的内部资源...", deviceId);
        stopReportingTask(true); // true - 关闭调度器
        if (client != null && client.isConnected()) {
            try {
                // 发布最后一次 offline 状态 (尽力而为)
                publishStatus("offline");
                // (可选) 发布注销信息
                // publishUnregisterInfo();
                // 短暂等待消息发出
                safeSleep(200);
                client.disconnect();
                logger.info("设备 [{}] MQTT 连接已断开。", deviceId);
            } catch (MqttException e) {
                logger.error("设备 [{}] 断开 MQTT 连接时出错: {}", deviceId, e.getMessage());
            }
        }
        if (client != null) {
            try {
                client.close(); // 关闭客户端实例
                logger.info("设备 [{}] MQTT 客户端已关闭。", deviceId);
            } catch (MqttException e) {
                logger.error("设备 [{}] 关闭 MQTT 客户端时出错: {}", deviceId, e.getMessage());
            }
        }
        connected.set(false); // 确保状态更新
    }

    /**
     * 安全地让线程休眠指定毫秒数，处理 InterruptedException。
     * @param millis 休眠时间（毫秒）
     */
    private void safeSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态
        }
    }
}