package com.example.demo1.controller;

import com.example.demo1.service.SseNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sse")
// 如果需要跨域，仍然需要配置 CORS
//@CrossOrigin(origins = "http://localhost:5175", allowCredentials = "true")
public class SseController {

    private static final Logger logger = LoggerFactory.getLogger(SseController.class);

    @Autowired
    private SseNotificationService sseNotificationService;

    // produces = MediaType.TEXT_EVENT_STREAM_VALUE 表示这是一个 SSE 端点
    @GetMapping(value = "/mqtt", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleMqttSse() {
        // 创建 SseEmitter，设置一个较长的超时时间（例如 1 小时），或者不设置让服务器默认处理
        // 超时时间是为了防止连接无限期挂起，即使客户端已断开但服务器未感知
        SseEmitter emitter = new SseEmitter(3600_000L); // 1 hour timeout
        logger.info("Creating new SSE Emitter for MQTT");

        // 将 emitter 添加到管理列表
        sseNotificationService.addEmitter(emitter);

        logger.info("SSE Emitter created and registered");
        return emitter;
    }
}