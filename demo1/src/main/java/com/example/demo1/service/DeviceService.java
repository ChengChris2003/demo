package com.example.demo1.service; // **确认包名**

import com.example.demo1.entity.Device;
import com.example.demo1.repository.DeviceRepository; // **确认导入**
// import com.example.demo1.service.MqttService; // **如果需要发送 MQTT 命令，取消注释并确保注入**
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service // **确认注解**
public class DeviceService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);

    @Autowired
    private DeviceRepository deviceRepository; // **确认注入**

    // @Autowired
    // private MqttService mqttService;

    @Transactional
    public Device handleDeviceRegistrationOrUpdate(String deviceUid, String deviceType, String initialStatus) {
        Optional<Device> existingDeviceOpt = deviceRepository.findByDeviceUid(deviceUid); // **确认 Repository 有此方法**
        Device device;
        if (existingDeviceOpt.isPresent()) {
            device = existingDeviceOpt.get();
            logger.info("设备 [{}] 已存在于数据库，更新状态为 '{}'", deviceUid, initialStatus);
            if (initialStatus != null && !initialStatus.equalsIgnoreCase(device.getStatus())) {
                device.setStatus(initialStatus);
            }
        } else {
            logger.info("数据库中未找到设备 UID [{}]，正在创建新记录... 类型: '{}', 状态: '{}'", deviceUid, deviceType, initialStatus);
            device = new Device(deviceUid, deviceType, initialStatus, deviceUid);
        }
        return deviceRepository.save(device);
    }

    @Transactional
    public void updateDeviceStatusByUid(String deviceUid, String status) {
        Optional<Device> deviceOpt = deviceRepository.findByDeviceUid(deviceUid); // **确认 Repository 有此方法**
        if (deviceOpt.isPresent()) {
            Device device = deviceOpt.get();
            if (status != null && !status.equalsIgnoreCase(device.getStatus())) {
                logger.info("通过 MQTT 更新数据库中设备 [{}] 的状态从 '{}' 到 '{}'", deviceUid, device.getStatus(), status);
                device.setStatus(status);
                deviceRepository.save(device);
            } else if (status == null) {
                logger.warn("尝试将设备 [{}] 的状态更新为 null，操作已跳过。", deviceUid);
            } else {
                logger.debug("设备 [{}] 的状态已经是 '{}'，无需更新数据库。", deviceUid, status);
            }
        } else {
            logger.warn("尝试通过 MQTT 更新状态，但未在数据库中找到设备 UID: {}", deviceUid);
        }
    }

    @Transactional
    public Device registerDevice(Device device) {
        String uidToCheck = device.getDeviceUid();
        if (uidToCheck == null || uidToCheck.trim().isEmpty()) {
            if (device.getDeviceName() != null && !device.getDeviceName().trim().isEmpty()) {
                uidToCheck = device.getDeviceName().trim();
                logger.warn("手动注册设备时 deviceUid 为空，将使用 deviceName '{}' 作为 deviceUid。", uidToCheck);
                device.setDeviceUid(uidToCheck);
            } else {
                logger.error("手动注册设备失败：必须提供 deviceUid 或 deviceName。");
                throw new IllegalArgumentException("Device must have a deviceUid or a non-empty deviceName for registration.");
            }
        } else {
            uidToCheck = uidToCheck.trim();
            device.setDeviceUid(uidToCheck);
        }

        if (deviceRepository.existsByDeviceUid(uidToCheck)) { // **确认 Repository 有此方法**
            logger.error("尝试手动注册的设备 UID '{}' 已存在于数据库中！", uidToCheck);
            throw new IllegalArgumentException("Device with UID '" + uidToCheck + "' already exists.");
        }
        logger.info("正在手动注册设备，UID: '{}'", uidToCheck);
        return deviceRepository.save(device);
    }

    public List<Device> getAllDevices() {
        logger.debug("正在获取所有设备列表...");
        return deviceRepository.findAll();
    }

    public Optional<Device> getDeviceById(Long id) {
        logger.debug("正在根据数据库 ID '{}' 获取设备...", id);
        return deviceRepository.findById(id);
    }

    @Transactional
    public Device updateDevice(Long id, Device deviceDetails) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> {
                    logger.error("尝试更新但未找到设备 ID: {}", id);
                    return new RuntimeException("设备未找到 ID: " + id);
                });

        logger.info("正在更新数据库中设备 ID: {}, UID: {}", id, device.getDeviceUid());

        if (deviceDetails.getDeviceUid() != null && !device.getDeviceUid().equals(deviceDetails.getDeviceUid())) {
            logger.warn("不允许通过此 API 修改设备的 deviceUid (尝试从 '{}' 修改为 '{}')", device.getDeviceUid(), deviceDetails.getDeviceUid());
            // 明确阻止修改 UID
            throw new IllegalArgumentException("Cannot change deviceUid via this method.");
        }

        if(deviceDetails.getDeviceName() != null) device.setDeviceName(deviceDetails.getDeviceName());
        if(deviceDetails.getDeviceType() != null) device.setDeviceType(deviceDetails.getDeviceType());
        if(deviceDetails.getStatus() != null) device.setStatus(deviceDetails.getStatus());

        return deviceRepository.save(device);
    }

    @Transactional
    public void deleteDevice(Long id) {
        Optional<Device> deviceOpt = deviceRepository.findById(id);
        if (deviceOpt.isPresent()) {
            String deviceUid = deviceOpt.get().getDeviceUid();
            logger.info("准备从数据库删除设备 ID: {}, UID: {}", id, deviceUid);
            // 可选：发送 MQTT 停止命令
            // try { if (mqttService != null) { ... } } catch (Exception e) { ... }
            deviceRepository.deleteById(id);
            logger.info("设备 ID: {}, UID: {} 已从数据库删除。", id, deviceUid);
        } else {
            logger.warn("尝试删除但未在数据库中找到设备 ID: {}", id);
        }
    }

    // *** 确认此方法存在 ***
    public boolean existsByDeviceUid(String deviceUid) {
        // 直接调用 Repository 的方法
        return deviceRepository.existsByDeviceUid(deviceUid); // **确认 Repository 有此方法**
    }

    @Transactional
    public void updateDeviceStatusByStringId(String deviceId, String status) {
        this.updateDeviceStatusByUid(deviceId, status);
    }
}