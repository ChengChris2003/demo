package com.example.demo1.controller; // **确认包名**

import com.example.demo1.entity.Device; // **确认导入**
import com.example.demo1.service.DeviceService; // **确认导入**
import com.example.demo1.service.MqttService; // **确认导入**
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/devices")
public class DeviceController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

    @Autowired
    private DeviceService deviceService; // **确认注入**

    @Autowired
    private MqttService mqttService; // **确认注入**

    @PostMapping("/register")
    public ResponseEntity<?> registerDevice(@RequestBody Device device) {
        try {
            Device registeredDevice = deviceService.registerDevice(device);
            return ResponseEntity.ok(registeredDevice);
        } catch (IllegalArgumentException e) {
            logger.warn("手动注册设备失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("手动注册设备时发生意外错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "注册设备时发生内部错误"));
        }
    }

    @GetMapping("/")
    public ResponseEntity<List<Device>> getAllDevices() {
        try {
            List<Device> devices = deviceService.getAllDevices();
            return ResponseEntity.ok(devices);
        } catch (Exception e) {
            logger.error("获取所有设备列表时出错", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Device> getDevice(@PathVariable Long id) {
        Optional<Device> deviceOpt = deviceService.getDeviceById(id);
        // 使用 map 和 orElseGet 避免歧义和异常转 500
        return deviceOpt.map(ResponseEntity::ok)
                .orElseGet(() -> {
                    logger.warn("尝试获取但未找到设备 ID: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDevice(@PathVariable Long id, @RequestBody Device deviceDetails) {
        try {
            Device updatedDevice = deviceService.updateDevice(id, deviceDetails);
            return ResponseEntity.ok(updatedDevice);
        } catch (IllegalArgumentException e) { // **先捕获子类 IllegalArgumentException**
            logger.warn("更新设备参数错误: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) { // **再捕获父类 RuntimeException** (例如找不到设备)
            logger.warn("更新设备时发生运行时错误: {}", e.getMessage());
            // 可以根据消息判断是否是 "未找到" 并返回 404
            if (e.getMessage() != null && e.getMessage().contains("未找到")) { // 假设 Service 中抛出的异常包含 "未找到"
                return ResponseEntity.notFound().build();
            }
            // 其他 RuntimeException 返回 500 或 400
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新设备时出错: " + e.getMessage()));
        } catch (Exception e) { // **最后捕获其他所有可能的受检异常 (如果 try 块中可能抛出)**
            logger.error("更新设备 ID {} 时发生意外错误", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新设备时发生内部错误"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDevice(@PathVariable Long id) {
        try {
            deviceService.deleteDevice(id);
            return ResponseEntity.ok(Map.of("message", "设备 ID " + id + " 已从平台删除"));
        } catch (Exception e) {
            logger.error("删除设备 ID {} 时发生意外错误", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "删除设备时发生内部错误"));
        }
    }

    @PostMapping("/{deviceUid}/command")
    public ResponseEntity<Map<String, String>> sendCommandToDevice(
            @PathVariable String deviceUid,
            @RequestBody Map<String, String> commandPayload) {

        String command = commandPayload.get("command");
        if (command == null || command.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体中缺少 'command' 字段或字段值为空"));
        }
        command = command.trim().toUpperCase();

        if (!command.equals("ON") && !command.equals("OFF")) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的命令，只支持 'ON' 或 'OFF'"));
        }

        // **确认 DeviceService 中有 existsByDeviceUid 方法**
        if (!deviceService.existsByDeviceUid(deviceUid)) {
            logger.warn("尝试向不存在的设备 UID [{}] 发送命令", deviceUid);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "未找到具有该 UID 的设备"));
        }

        try {
            String commandTopic = "device/command/" + deviceUid;
            mqttService.publish(commandTopic, command);
            logger.info("已向设备 [{}] 的主题 [{}] 发送命令: {}", deviceUid, commandTopic, command);
            return ResponseEntity.ok(Map.of("message", "命令 '" + command + "' 已发送至设备 " + deviceUid));
        } catch (IllegalStateException e) {
            logger.error("向设备 [{}] 发送命令失败，MQTT 客户端未连接。", deviceUid, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "MQTT 服务当前不可用"));
        } catch (Exception e) {
            logger.error("向设备 [{}] 发送命令时发生意外错误: {}", deviceUid, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "发送命令时发生内部错误"));
        }
    }
}