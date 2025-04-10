package com.example.demo1.repository;

import java.util.Optional; // 导入 Optional
import com.example.demo1.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    // 根据需要添加自定义查询方法，例如通过设备名称查找

    Optional<Device> findByDeviceUid(String deviceUid);

    // 检查具有给定 deviceUid 的设备是否存在
    boolean existsByDeviceUid(String deviceUid);
}
