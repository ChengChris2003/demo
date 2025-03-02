package com.example.demo1.service;

import jakarta.annotation.PostConstruct;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Service;

@Service
public class MqttService {

    private final String brokerUrl = "tcp://localhost:1883"; // 本地 EMQX 地址
    private final String clientId = "spring-boot-client";
    private MqttClient client;

    // 在 MqttService.java 中，init() 方法内增加新的订阅
    @PostConstruct
    public void init() {
        try {
            client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            // 如果有认证，这里可设置用户名和密码
            // options.setUserName("your_username");
            // options.setPassword("your_password".toCharArray());
            client.connect(options);
            System.out.println("MQTT 连接成功！");

            // 订阅业务相关主题，例如 test/topic 用于调试
            client.subscribe("test/topic", (topic, msg) -> {
                System.out.println("收到主题 " + topic + " 的消息：" + new String(msg.getPayload()));
            });

            // 订阅设备上报主题 device/report/#
            client.subscribe("device/report/#", (topic, msg) -> {
                String payload = new String(msg.getPayload());
                // 假设 topic 格式：device/report/{deviceId}
                String[] parts = topic.split("/");
                if (parts.length >= 3) {
                    String deviceId = parts[2];
                    // 这里可以调用 DeviceService 更新设备状态或者记录上报数据
                    System.out.println("设备 " + deviceId + " 上报数据: " + payload);
                    // 示例：若上报数据是状态信息，则更新设备状态
                    // 注意：这里建议将设备上报的数据格式统一为 JSON，再解析后存入数据库
                }
            });

            // 全局回调（选填）
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.out.println("连接丢失: " + cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    // 这里的消息处理在订阅时已完成
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    System.out.println("消息已送达: " + token.isComplete());
                }
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void publish(String topic, String payload) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(1); // 根据需要设置服务质量
            client.publish(topic, message);
            System.out.println("已发布消息到主题：" + topic);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
