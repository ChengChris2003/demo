package com.example.demo1.service;

import com.example.demo1.service.DeviceService; // *** 1. 导入 DeviceService ***
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

import java.util.Map; // 用于解析状态 JSON

@Service
public class MqttService {

    private static final Logger logger = LoggerFactory.getLogger(MqttService.class);

    private final String brokerUrl = "tcp://localhost:1883"; // 本地 EMQX 地址
    private final String clientId = "spring-boot-client-sse"; // 稍微区分一下 Client ID
    private MqttClient client;

    @Autowired
    private SseNotificationService sseNotificationService; // 注入 SSE 服务

    @Autowired
    private ObjectMapper objectMapper; // 注入 ObjectMapper

    @Autowired // *** 2. 注入 DeviceService ***
    private DeviceService deviceService; // 用于更新数据库中的设备状态

    // 定义一个内部类或单独的类来封装发送给前端的数据
    private static class MqttMessagePayload {
        public String topic;
        public String payload;
        public long timestamp;

        public MqttMessagePayload(String topic, String payload, long timestamp) {
            this.topic = topic;
            this.payload = payload;
            this.timestamp = timestamp;
        }
        // public 字段足够 Jackson 序列化
    }

    @PostConstruct
    public void init() {
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            // options.setUserName("your_username");
            // options.setPassword("your_password".toCharArray());

            // 设置 MqttCallback 优先于 connect
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    logger.warn("MQTT 连接丢失: ", cause);
                    // 可在此处添加重连逻辑或通知
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    // 通常在 subscribe 的 lambda 中处理
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    try {
                        logger.debug("消息已送达: {}, Token: {}", token.isComplete(), token.getMessageId());
                    } catch (Exception e) {
                        logger.debug("消息已送达: {}", token.isComplete());
                    }
                }
            });

            client.connect(options);
            logger.info("MQTT 连接成功！Broker: {}, ClientID: {}", brokerUrl, clientId);


            // --- 订阅 test/topic ---
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

            // --- 订阅设备上报数据 device/report/# ---
            client.subscribe("device/report/#", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                String deviceId = extractDeviceIdFromTopic(topic, 2); // 提取 Device ID

                logger.info("设备 '{}' 上报数据 (SSE), Topic='{}', Payload='{}'", deviceId, topic, payload);

                MqttMessagePayload messageData = new MqttMessagePayload(topic, payload, System.currentTimeMillis());
                try {
                    String jsonPayload = objectMapper.writeValueAsString(messageData);
                    sseNotificationService.sendSseEvent("mqttMessage", jsonPayload);
                    logger.debug("Sent message (device/report) to SSE clients: {}", jsonPayload);

                    // *** 可选: 根据上报数据更新设备状态或最后活跃时间 ***
                    // if (!"unknown".equals(deviceId)) {
                    //    deviceService.updateDeviceLastReport(deviceId, payload); // 需要实现此方法
                    // }

                } catch (JsonProcessingException e) {
                    logger.error("转换 MQTT 消息为 JSON 时出错 (device/report/#): {}", e.getMessage(), e);
                }
            });
            logger.info("已订阅主题: device/report/#");


            // *** 3. 新增: 订阅设备状态 device/status/# ***
            client.subscribe("device/status/#", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                String deviceId = extractDeviceIdFromTopic(topic, 2); // 提取 Device ID

                logger.info("收到设备状态消息 (SSE), Device='{}', Topic='{}', Payload='{}'", deviceId, topic, payload);

                try {
                    // a) 将原始状态消息推送到前端
                    MqttMessagePayload messageData = new MqttMessagePayload(topic, payload, System.currentTimeMillis());
                    String jsonPayload = objectMapper.writeValueAsString(messageData);
                    sseNotificationService.sendSseEvent("mqttMessage", jsonPayload); // 仍然使用 "mqttMessage" 事件名
                    logger.debug("Sent status message (device/status) to SSE clients: {}", jsonPayload);

                    // b) 解析状态消息并更新数据库 (可选但推荐)
                    if (!"unknown".equals(deviceId)) {
                        try {
                            // 假设状态消息是 JSON: {"deviceId":"...", "status":"online/offline", ...}
                            Map<String, Object> statusMap = objectMapper.readValue(payload, Map.class);
                            String status = (String) statusMap.get("status");

                            if (status != null) {
                                // *** 调用 DeviceService 更新数据库状态 ***
                                // logger.info("尝试更新设备 [{}] 的数据库状态为: {}", deviceId, status);
                                // deviceService.updateDeviceStatusByStringId(deviceId, status); // !!! 需要你在 DeviceService 和 Repository 中实现此功能 !!!
                                logger.warn("更新数据库设备状态的功能 (updateDeviceStatusByStringId) 尚未实现或已注释掉"); // 提示开发者

                            } else {
                                logger.warn("收到的设备 [{}] 状态消息缺少 'status' 字段: {}", deviceId, payload);
                            }
                        } catch (Exception parseException) {
                            logger.error("解析设备 [{}] 状态消息 JSON 时出错: {}", deviceId, parseException.getMessage());
                        }
                    } else {
                        logger.warn("无法从主题 '{}' 中提取有效的 Device ID 来更新状态", topic);
                    }

                } catch (JsonProcessingException e) {
                    logger.error("转换状态消息为 JSON 时出错 (device/status/#): {}", e.getMessage(), e);
                }
            });
            logger.info("已订阅主题: device/status/#"); // *** 4. 添加日志确认 ***


        } catch (MqttException e) {
            logger.error("初始化 MQTT 客户端或订阅主题时出错: {}", e.getMessage(), e);
        }
    }

    // 提取 Device ID 的辅助方法 (根据你的主题结构调整索引)
    private String extractDeviceIdFromTopic(String topic, int index) {
        try {
            String[] parts = topic.split("/");
            if (parts.length > index) {
                return parts[index];
            }
        } catch (Exception e){
            logger.error("从主题 '{}' 提取 Device ID 时出错: {}", topic, e.getMessage());
        }
        return "unknown";
    }


    // publish 方法保持不变
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
        }
    }

    // disconnect 方法可选，保持不变
    // @PreDestroy
    // public void disconnect() { ... }
}