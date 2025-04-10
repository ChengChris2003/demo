package com.example.demo1.controller;

import com.example.demo1.service.MqttService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mqtt")
//@CrossOrigin(origins = "http://localhost:5175") // 允许来自 Vite 开发服务器的请求
public class MqttController {

    @Autowired
    private MqttService mqttService;

    // POST 请求：发布消息到指定主题
    @PostMapping("/publish")
    public ResponseEntity<String> publish(@RequestParam String topic, @RequestParam String message) {
        mqttService.publish(topic, message);
        return ResponseEntity.ok("消息已发布到主题：" + topic);
    }
}
