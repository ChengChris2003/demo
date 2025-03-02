package com.example.demo1.service;

import com.example.demo1.entity.Device;
import com.example.demo1.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DeviceService {

    @Autowired
    private DeviceRepository deviceRepository;

    // 注册设备
    public Device registerDevice(Device device) {
        return deviceRepository.save(device);
    }

    // 查询所有设备
    public List<Device> getAllDevices() {
        return deviceRepository.findAll();
    }

    // 根据ID查询设备
    public Optional<Device> getDeviceById(Long id) {
        return deviceRepository.findById(id);
    }

    // 更新设备信息
    public Device updateDevice(Long id, Device deviceDetails) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("设备未找到"));
        device.setDeviceName(deviceDetails.getDeviceName());
        device.setDeviceType(deviceDetails.getDeviceType());
        device.setStatus(deviceDetails.getStatus());
        return deviceRepository.save(device);
    }

    // 删除设备
    public void deleteDevice(Long id) {
        deviceRepository.deleteById(id);
    }
}
