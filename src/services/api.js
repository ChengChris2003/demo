// src/services/api.js
import axios from 'axios';
import { ElMessage } from 'element-plus';

const apiClient = axios.create({
  baseURL: 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
  },
});

// 增强的错误拦截器
apiClient.interceptors.response.use(
  response => response,
  error => {
    console.error('API Error:', error.response || error.message || error);
    const errorMsg = error.response?.data?.error ||        // 优先取 error 字段
                     error.response?.data?.message ||      // 其次取 message
                     error.message ||                      // 再次取 Axios 错误
                     '请求失败，请检查网络或联系管理员';
    ElMessage({
      message: errorMsg,
      type: 'error',
      duration: 5 * 1000,
    });
    return Promise.reject(error);
  }
);

export default {
  // --- Device API ---
  getAllDevices() {
    return apiClient.get('/devices/');
  },
  registerDevice(device) {
    return apiClient.post('/devices/register', device);
  },
  getDeviceById(id) {
    return apiClient.get(`/devices/${id}`);
  },
  updateDevice(id, device) {
    return apiClient.put(`/devices/${id}`, device);
  },
  deleteDevice(id) {
    return apiClient.delete(`/devices/${id}`);
  },

  // --- 新增设备控制接口 ---
  sendCommandToDevice(deviceUid, command) {
    const commandPayload = { command: command }; // command 应为 'ON'/'OFF'
    return apiClient.post(`/devices/${deviceUid}/command`, commandPayload);
  },

  // --- MQTT 通用发布接口 ---
  publishMqttMessage(topic, message) {
    return apiClient.post('/mqtt/publish', null, {
      params: { topic, message }
    });
  }

  // --- 移除过时的消息获取占位符 ---
  /* 原 getRecentMqttMessages() 已移除，SSE 不需要主动 API 调用 */
};