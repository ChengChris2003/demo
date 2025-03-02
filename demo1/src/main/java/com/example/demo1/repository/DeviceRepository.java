package com.example.demo1.repository;

import com.example.demo1.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    // 根据需要添加自定义查询方法，例如通过设备名称查找
}
