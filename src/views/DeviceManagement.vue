<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>设备列表</span>
          <!-- 手动添加设备按钮 (根据需要保留或移除) -->
          <el-button type="primary" :icon="Plus" @click="handleAdd">添加设备 (手动)</el-button>
        </div>
      </template>

       <!-- 添加一个区域显示实时消息通道状态 -->
       <el-alert v-if="sseStatus !== 'CONNECTED'"
           :title="sseStatus === 'ERROR' ? '实时消息通道连接错误' : '正在连接实时消息通道...'"
           :type="sseStatus === 'ERROR' ? 'error' : 'warning'"
           show-icon
           :closable="false"
           style="margin-bottom: 15px;">
       </el-alert>
       <el-alert v-else title="设备状态实时更新中" type="success" show-icon :closable="false" style="margin-bottom: 15px;"></el-alert>


      <el-table :data="devices" stripe v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="DB ID" width="80" />
        <el-table-column prop="deviceUid" label="设备 UID" width="180" /> <!-- 显示设备 UID -->
        <el-table-column prop="deviceName" label="设备名称" />
        <el-table-column prop="deviceType" label="设备类型" />
        <el-table-column prop="status" label="状态 (来自DB/SSE)">
           <template #default="scope">
             <!-- 优先显示实时状态 sseDeviceStatus，如果不存在则显示数据库状态 scope.row.status -->
             <el-tag :type="getStatusTagType(sseDeviceStatus[scope.row.deviceUid] || scope.row.status)">
               {{ sseDeviceStatus[scope.row.deviceUid] || scope.row.status || '未知' }}
             </el-tag>
           </template>
        </el-table-column>
        <el-table-column label="操作" width="280"> <!-- 增加宽度给新按钮 -->
          <template #default="scope">
            <!-- 新增：开启/关闭按钮 -->
            <el-button size="small" type="success" :icon="VideoPlay" @click="handleSendCommand(scope.row.deviceUid, 'ON')" :disabled="commandLoading[scope.row.deviceUid]">开启</el-button>
            <el-button size="small" type="warning" :icon="VideoPause" @click="handleSendCommand(scope.row.deviceUid, 'OFF')" :disabled="commandLoading[scope.row.deviceUid]">关闭</el-button>
            <!-- 保留：编辑按钮 -->
            <el-button size="small" :icon="Edit" @click="handleEdit(scope.row)">编辑</el-button>
            <!-- 保留：删除按钮 -->
            <el-popconfirm
              title="确定要删除此设备记录吗？(不会停止模拟设备)"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(scope.row.id)"
            >
              <template #reference>
                <el-button size="small" type="danger" :icon="Delete">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <!-- 可以添加分页组件 -->
    </el-card>

    <!-- 添加/编辑设备弹窗 (保持不变) -->
    <DeviceForm
      v-model:visible="dialogVisible"
      :device-data="currentDevice"
      :mode="dialogMode"
      @submit="handleFormSubmit"
    />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete, VideoPlay, VideoPause } from '@element-plus/icons-vue'; // 导入新图标
import api from '@/services/api';
import DeviceForm from '@/components/DeviceForm.vue';

// --- 设备列表相关状态 ---
const devices = ref([]);
const loading = ref(false);
const dialogVisible = ref(false);
const dialogMode = ref('add');
const currentDevice = ref(null);
const commandLoading = reactive({}); // 用于跟踪每个设备命令按钮的加载状态 { deviceUid: boolean }

// --- SSE 相关状态 ---
const sseDeviceStatus = reactive({}); // 存储通过 SSE 更新的设备状态 { deviceUid: 'online'/'offline' }
const sseStatus = ref('DISCONNECTED'); // SSE 连接状态
let sseEventSource = null; // SSE EventSource 实例


// --- 方法：获取设备列表 ---
const fetchDevices = async () => {
  loading.value = true;
  try {
    const response = await api.getAllDevices();
    devices.value = response.data;
    // 初始化 commandLoading 状态
    devices.value.forEach(d => {
        if(d.deviceUid) commandLoading[d.deviceUid] = false;
    });
  } catch (error) {
    console.error("获取设备列表失败:", error);
    // 错误由拦截器处理
  } finally {
    loading.value = false;
  }
};

// --- 方法：根据状态获取 Tag 类型 ---
const getStatusTagType = (status) => {
  if (!status) return 'info';
  status = status.toLowerCase();
  if (status === 'online') return 'success';
  if (status === 'offline') return 'danger';
  return 'warning'; // 其他状态
};

// --- 方法：处理手动添加/编辑 ---
const handleAdd = () => {
  dialogMode.value = 'add';
  currentDevice.value = null;
  dialogVisible.value = true;
};
const handleEdit = (device) => {
  dialogMode.value = 'edit';
  currentDevice.value = { ...device };
  dialogVisible.value = true;
};
const handleDelete = async (id) => {
  // loading.value = true; // 可以为删除添加单独的加载状态
  try {
    await api.deleteDevice(id);
    ElMessage({ type: 'success', message: '设备记录删除成功!' });
    fetchDevices(); // 刷新列表
  } catch (error) {
     console.error("删除设备记录失败:", error);
  } finally {
     // loading.value = false;
  }
};
const handleFormSubmit = () => {
  dialogVisible.value = false;
  fetchDevices();
};

// --- 新增：方法：发送 ON/OFF 命令 ---
const handleSendCommand = async (deviceUid, command) => {
    if (!deviceUid) return;
    commandLoading[deviceUid] = true; // 开始加载
    try {
        const response = await api.sendCommandToDevice(deviceUid, command); // 调用新的 API
        ElMessage.success(response.data.message || `命令 ${command} 发送成功`);
        // 状态会通过 SSE 自动更新，无需手动修改或刷新列表
    } catch (error) {
        console.error(`发送命令 ${command} 到 ${deviceUid} 失败:`, error);
        // 错误消息由 Axios 拦截器处理并显示
    } finally {
        commandLoading[deviceUid] = false; // 结束加载
    }
}

// --- SSE 连接和消息处理逻辑 ---
const connectRealtimeStatus = () => {
    const sseUrl = 'http://localhost:8080/sse/mqtt'; // 与 MqttControl.vue 使用相同的 SSE 端点
    if (typeof(EventSource) === "undefined") {
        sseStatus.value = 'ERROR';
        console.error("浏览器不支持 SSE");
        return;
    }
    if (sseEventSource) sseEventSource.close();

    sseStatus.value = 'CONNECTING';
    sseEventSource = new EventSource(sseUrl, { withCredentials: true });

    sseEventSource.onopen = () => {
        console.log("设备管理页 SSE 连接已打开");
        sseStatus.value = 'CONNECTED';
    };

    // 监听所有 mqttMessage 事件
    sseEventSource.addEventListener('mqttMessage', (event) => {
        try {
            const msg = JSON.parse(event.data);
            // *** 关键：只处理 device/status/# 主题的消息来更新实时状态 ***
            if (msg.topic && msg.topic.startsWith('device/status/')) {
                 // 解析状态消息体 {"deviceId":"...", "status":"online/offline", ...}
                 try {
                     const statusPayload = JSON.parse(msg.payload); // 假设 payload 也是 JSON
                     const uid = statusPayload.deviceId;
                     const status = statusPayload.status;
                     if (uid && status) {
                         console.log(`通过 SSE 更新设备 [${uid}] 状态为: ${status}`);
                         sseDeviceStatus[uid] = status.toLowerCase(); // 更新实时状态对象
                         // 强制 Vue 更新视图（在某些复杂情况下可能需要）
                         // nextTick(() => { /* 可以在此强制更新 */ });
                     }
                 } catch (parseError) {
                      // 如果 payload 不是 JSON，尝试直接使用它作为状态？或者忽略？
                       logger.warn("解析 device/status payload 出错:", parseError, "原始 payload:", msg.payload);
                       // 尝试从 topic 提取 UID 并直接使用 payload 作为状态（如果约定好）
                       const uidFromTopic = msg.topic.split('/')[2];
                       if (uidFromTopic) {
                            sseDeviceStatus[uidFromTopic] = msg.payload.toLowerCase();
                            logger.info(`通过 SSE 更新设备 [${uidFromTopic}] 状态为 (直接使用 payload): ${msg.payload}`);
                       }
                 }
            }
            // 可以选择性地处理 device/report 或 device/lifecycle 消息用于其他目的
            // else if (msg.topic && msg.topic.startsWith('device/report/')) { ... }

        } catch (e) {
            console.error("处理 SSE 消息时出错:", e, "原始数据:", event.data);
        }
    });

    sseEventSource.onerror = (error) => {
        console.error("设备管理页 SSE 连接错误:", error);
        sseStatus.value = 'ERROR';
        if (sseEventSource && sseEventSource.readyState === EventSource.CLOSED) {
            sseStatus.value = 'DISCONNECTED';
            sseEventSource = null;
            // 可以添加重连逻辑
             // setTimeout(connectRealtimeStatus, 5000); // 5秒后尝试重连
        }
    };
};

const disconnectRealtimeStatus = () => {
    if (sseEventSource) {
        sseEventSource.close();
        sseEventSource = null;
        console.log("设备管理页 SSE 连接已关闭");
    }
    sseStatus.value = 'DISCONNECTED';
};

// --- 生命周期钩子 ---
onMounted(() => {
  fetchDevices(); // 获取初始设备列表
  connectRealtimeStatus(); // 建立 SSE 连接以接收实时状态更新
});

onUnmounted(() => {
  disconnectRealtimeStatus(); // 组件卸载时断开 SSE 连接
});

</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/* 给操作按钮之间增加一点间距 */
.el-table .el-button + .el-button {
    margin-left: 8px;
}
.el-table .el-button + .el-popconfirm {
    margin-left: 8px;
}
.el-popconfirm + .el-button {
     margin-left: 8px;
}

</style>