package com.example.demo1.service;

import com.example.demo1.service.DeviceService; // 确保 DeviceService 已导入
import com.example.demo1.service.SseNotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map; // 用于解析 JSON

@Service
public class MqttService {

    private static final Logger logger = LoggerFactory.getLogger(MqttService.class);

    private final String brokerUrl = "tcp://localhost:1883"; // 本地 EMQX 地址
    private final String clientId = "spring-boot-client-sse"; // 区分 Client ID
    private MqttClient client;

    @Autowired
    private SseNotificationService sseNotificationService; // 注入 SSE 服务

    @Autowired
    private ObjectMapper objectMapper; // 注入 ObjectMapper

    @Autowired // 确保 DeviceService 已注入
    private DeviceService deviceService; // 用于处理注册和状态更新

    // 内部类用于封装发送给前端的数据
    private static class MqttMessagePayload {
        public String topic;
        public String payload;
        public long timestamp;

        public MqttMessagePayload(String topic, String payload, long timestamp) {
            this.topic = topic;
            this.payload = payload;
            this.timestamp = timestamp;
        }
    }

    @PostConstruct
    public void init() {
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            // options.setUserName("your_username");
            // options.setPassword("your_password".toCharArray());

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    logger.warn("MQTT 连接丢失: ", cause);
                }
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception { }
                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    try {
                        logger.debug("消息已送达: {}, Token: {}", token.isComplete(), token.getMessageId());
                    } catch (Exception e) { logger.debug("消息已送达: {}", token.isComplete()); }
                }
            });

            client.connect(options);
            logger.info("MQTT 连接成功！Broker: {}, ClientID: {}", brokerUrl, clientId);

            // --- 订阅 test/topic (保留) ---
            client.subscribe("test/topic", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                logger.info("收到主题 '{}' 的消息 (SSE): {}", topic, payload);
                MqttMessagePayload messageData = new MqttMessagePayload(topic, payload, System.currentTimeMillis());
                try {
                    String jsonPayload = objectMapper.writeValueAsString(messageData);
                    sseNotificationService.sendSseEvent("mqttMessage", jsonPayload);
                    logger.debug("Sent message (test/topic) to SSE clients: {}", jsonPayload);
                } catch (JsonProcessingException e) {
                    logger.error("转换 MQTT 消息为 JSON 时出错 (test/topic): {}", e.getMessage(), e);
                }
            });
            logger.info("已订阅主题: test/topic");

            // --- 订阅设备上报数据 device/report/# (保留) ---
            client.subscribe("device/report/#", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                String deviceId = extractDeviceIdFromTopic(topic, 2);
                logger.info("设备 '{}' 上报数据 (SSE), Topic='{}', Payload='{}'", deviceId, topic, payload);
                MqttMessagePayload messageData = new MqttMessagePayload(topic, payload, System.currentTimeMillis());
                try {
                    String jsonPayload = objectMapper.writeValueAsString(messageData);
                    sseNotificationService.sendSseEvent("mqttMessage", jsonPayload);
                    logger.debug("Sent message (device/report) to SSE clients: {}", jsonPayload);
                    // 可选: 更新最后活跃时间等
                    // if (!"unknown".equals(deviceId)) { deviceService.updateDeviceLastReport(deviceId); }
                } catch (JsonProcessingException e) {
                    logger.error("转换 MQTT 消息为 JSON 时出错 (device/report/#): {}", e.getMessage(), e);
                }
            });
            logger.info("已订阅主题: device/report/#");

            // --- 订阅设备状态 device/status/# (保留并调用 DeviceService) ---
            client.subscribe("device/status/#", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                String deviceUid = extractDeviceIdFromTopic(topic, 2);
                logger.info("收到设备状态消息 (SSE), DeviceUID='{}', Topic='{}', Payload='{}'", deviceUid, topic, payload);
                try {
                    // a) 推送给前端
                    MqttMessagePayload messageData = new MqttMessagePayload(topic, payload, System.currentTimeMillis());
                    String jsonPayload = objectMapper.writeValueAsString(messageData);
                    sseNotificationService.sendSseEvent("mqttMessage", jsonPayload);
                    logger.debug("Sent status message (device/status) to SSE clients: {}", jsonPayload);

                    // b) 更新数据库
                    if (!"unknown".equals(deviceUid)) {
                        try {
                            Map<String, Object> statusMap = objectMapper.readValue(payload, Map.class);
                            String status = (String) statusMap.get("status");
                            if (status != null) {
                                // *** 调用 DeviceService 更新数据库状态 ***
                                deviceService.updateDeviceStatusByUid(deviceUid, status); // 使用正确的 Service 方法
                            } else {
                                logger.warn("收到的设备 [{}] 状态消息缺少 'status' 字段: {}", deviceUid, payload);
                            }
                        } catch (Exception parseException) {
                            logger.error("解析设备 [{}] 状态消息 JSON 时出错: {}", deviceUid, parseException.getMessage());
                        }
                    } else {
                        logger.warn("无法从主题 '{}' 中提取有效的 Device UID 来更新状态", topic);
                    }
                } catch (JsonProcessingException e) {
                    logger.error("转换状态消息为 JSON 时出错 (device/status/#): {}", e.getMessage(), e);
                }
            });
            logger.info("已订阅主题: device/status/#");

            // *** 新增: 订阅设备注册/上线主题 device/lifecycle/register ***
            client.subscribe("device/lifecycle/register", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                logger.info("收到设备注册/上线消息, Topic='{}', Payload='{}'", topic, payload);
                try {
                    // 解析注册消息 JSON
                    Map<String, Object> registrationInfo = objectMapper.readValue(payload, Map.class);
                    String deviceUid = (String) registrationInfo.get("deviceId");
                    String deviceType = (String) registrationInfo.get("deviceType");
                    // 从消息中获取状态，如果消息中没有，则默认为 "online"
                    String status = (String) registrationInfo.getOrDefault("status", "online");

                    if (deviceUid != null && !deviceUid.isBlank() && deviceType != null && !deviceType.isBlank()) {
                        // *** 调用 DeviceService 处理注册或上线更新 ***
                        deviceService.handleDeviceRegistrationOrUpdate(deviceUid, deviceType, status);
                        // 可选：将注册事件也推送到前端（如果前端需要知道哪些设备刚注册）
                        // MqttMessagePayload regEventData = new MqttMessagePayload(topic, payload, System.currentTimeMillis());
                        // sseNotificationService.sendSseEvent("deviceRegistration", objectMapper.writeValueAsString(regEventData));
                    } else {
                        logger.warn("收到的注册消息格式不正确或缺少 deviceId/deviceType 字段: {}", payload);
                    }
                } catch (Exception e) {
                    logger.error("处理设备注册/上线消息时出错: {}", e.getMessage(), e);
                }
            });
            logger.info("已订阅主题: device/lifecycle/register"); // *** 确认订阅日志 ***


        } catch (MqttException e) {
            logger.error("初始化 MQTT 客户端或订阅主题时出错: {}", e.getMessage(), e);
        }
    }

    // 提取 Device ID 的辅助方法
    private String extractDeviceIdFromTopic(String topic, int index) {
        try {
            String[] parts = topic.split("/");
            if (parts.length > index) {
                // 做一些基本的 ID 合法性检查（例如非空）
                String potentialId = parts[index];
                if(potentialId != null && !potentialId.isBlank()){
                    return potentialId;
                }
            }
        } catch (Exception e){
            logger.error("从主题 '{}' 提取 Device ID 时出错: {}", topic, e.getMessage());
        }
        return "unknown"; // 返回一个明确的无效值
    }


    // publish 方法
    public void publish(String topic, String payload) {
        if (client == null || !client.isConnected()) {
            logger.warn("MQTT 客户端未连接，无法发布消息到主题: {}", topic);
            throw new IllegalStateException("MQTT client is not connected.");
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(1);
            client.publish(topic, message);
            logger.info("已发布消息到主题 '{}', Payload: '{}'", topic, payload);
        } catch (MqttException e) {
            logger.error("发布 MQTT 消息到主题 '{}' 时出错: {}", topic, e.getMessage(), e);
            // 可以考虑根据错误代码进行更细致的处理，例如判断是否连接丢失
            if (e.getReasonCode() == MqttException.REASON_CODE_CLIENT_NOT_CONNECTED) {
                logger.warn("发布失败，因为客户端未连接。");
                // 可能需要触发重连逻辑或通知
            }
        }
    }

    // disconnect 方法可选
    // @PreDestroy
    // public void disconnect() { ... }
}