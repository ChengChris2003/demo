package com.example.demo1.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import jakarta.persistence.Column;

@Entity
public class    Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceName;
    private String deviceType;
    private String status; // 可用于表示设备当前状态，例如“在线”、“离线”等

    // 构造方法
    public Device() {}

    public Device(String deviceName, String deviceType, String status) {
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.status = status;
    }


    @Column(unique = true, nullable = false) // 设备唯一标识符，来自模拟器，非空且唯一
    private String deviceUid;

    // *** 添加新的构造函数 (如果需要) ***
    public Device(String deviceName, String deviceType, String status, String deviceUid) {
        this.deviceName = deviceName; // 可以考虑让 deviceName 就等于 deviceUid
        this.deviceType = deviceType;
        this.status = status;
        this.deviceUid = deviceUid;
    }


    // *** 添加 getter 和 setter ***
    public String getDeviceUid() {
        return deviceUid;
    }

    public void setDeviceUid(String deviceUid) {
        this.deviceUid = deviceUid;
    }

    // Getter 和 Setter
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
