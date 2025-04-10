<template>
  <el-row :gutter="20">
    <!-- Publish Section (保持不变，用于通用发布/测试) -->
    <el-col :span="12">
      <el-card shadow="never">
        <template #header>发布 MQTT 消息 (通用)</template>
        <el-form ref="publishFormRef" :model="publishForm" :rules="publishRules" label-width="80px" v-loading="publishLoading">
          <el-form-item label="主题" prop="topic">
            <el-input v-model="publishForm.topic" placeholder="例如: device/command/some-id, test/topic"></el-input>
          </el-form-item>
          <el-form-item label="消息" prop="message">
            <el-input
              type="textarea"
              :rows="4"
              v-model="publishForm.message"
              placeholder="输入要发布的消息内容 (例如 ON, OFF, 或 JSON)"
            ></el-input>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handlePublish" :loading="publishLoading">发布</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </el-col>

    <!-- Receive Section (使用 SSE，显示所有后端转发的消息) -->
    <el-col :span="12">
      <el-card shadow="never">
         <template #header>
            <span>接收到的实时消息</span>
            <el-tag size="small" :type="connectionStatus === 'CONNECTED' ? 'success' : (connectionStatus === 'CONNECTING' ? 'warning' : 'danger')" style="float: right;">
              {{ connectionStatus }}
            </el-tag>
        </template>
        <div class="message-display-area" ref="messageAreaRef">
           <p v-if="receivedMessages.length === 0 && connectionStatus !== 'CONNECTED'" class="no-messages">
              {{ connectionStatus === 'ERROR' ? '连接错误，请检查后端或网络' : '尝试连接实时消息通道...' }}
           </p>
           <p v-if="receivedMessages.length === 0 && connectionStatus === 'CONNECTED'" class="no-messages">
              已连接，等待消息... (来自 test/topic, device/report/#, device/status/# 等)
           </p>
           <div v-for="(msg, index) in receivedMessages" :key="`${msg.timestamp}-${index}`" class="message-item">
              <el-tooltip :content="`Topic: ${msg.topic}`" placement="top">
                <span class="topic">[{{ truncateTopic(msg.topic) }}]</span>
              </el-tooltip>
              <span class="payload">{{ msg.payload }}</span>
              <span class="timestamp">{{ formatTimestamp(msg.timestamp) }}</span>
           </div>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue';
import { ElMessage } from 'element-plus';
import api from '@/services/api'; // 确保 api.js 路径正确

// --- Publish Logic (保持不变) ---
const publishFormRef = ref(null);
const publishLoading = ref(false);
const publishForm = reactive({
  topic: 'test/topic', // 默认值
  message: '',
});
const publishRules = reactive({
  topic: [{ required: true, message: '请输入主题', trigger: 'blur' }],
  message: [{ required: true, message: '请输入消息内容', trigger: 'blur' }],
});

const handlePublish = async () => {
  if (!publishFormRef.value) return;
  await publishFormRef.value.validate(async (valid) => {
    if (valid) {
      publishLoading.value = true;
      try {
        // 调用通用的后端发布接口
        await api.publishMqttMessage(publishForm.topic, publishForm.message);
        ElMessage({ type: 'success', message: '消息发布成功!' });
      } catch (error) {
         console.error("通用发布消息失败:", error);
         // 错误消息由 Axios 拦截器处理
      } finally {
        publishLoading.value = false;
      }
    }
  });
};

// --- Receive Logic (使用 SSE，与上次代码基本一致) ---
const receivedMessages = ref([]);
const messageAreaRef = ref(null);
const connectionStatus = ref('DISCONNECTED'); // DISCONNECTED, CONNECTING, CONNECTED, ERROR
let eventSource = null;

const connectSSE = () => {
  const sseUrl = 'http://localhost:8080/sse/mqtt'; // 后端 SSE 端点
  if (typeof(EventSource) === "undefined") { /* ... 省略浏览器不支持的处理 ... */ return; }
  if (eventSource) eventSource.close(); // 关闭旧连接

  console.log("开始连接 SSE:", sseUrl);
  connectionStatus.value = 'CONNECTING';
  eventSource = new EventSource(sseUrl, { withCredentials: true });

  eventSource.onopen = (event) => {
    console.log("SSE 连接已打开:", event);
    connectionStatus.value = 'CONNECTED';
    // ElMessage.success("实时消息通道已连接"); // 可以取消注释，如果需要每次连接都提示
  };

  eventSource.addEventListener('mqttMessage', (event) => { // 监听后端定义的名字
    console.log("收到 SSE 事件 [mqttMessage]:", event.data);
    try {
      const receivedMsg = JSON.parse(event.data);
      receivedMessages.value = [...receivedMessages.value, receivedMsg].slice(-50); // 保留最近 50 条
      nextTick(() => { // 滚动到底部
        if (messageAreaRef.value) messageAreaRef.value.scrollTop = messageAreaRef.value.scrollHeight;
      });
    } catch (e) { /* ... 省略 JSON 解析错误处理 ... */ }
  });

   eventSource.addEventListener('connection', (event) => { // 监听连接确认事件
       console.log("收到 SSE 事件 [connection]:", event.data);
   });

  eventSource.onerror = (error) => {
    console.error("SSE 连接错误:", error);
    connectionStatus.value = 'ERROR';
    if (eventSource && eventSource.readyState === EventSource.CLOSED) {
        console.log("SSE 连接已关闭，将不再自动重连。");
        connectionStatus.value = 'DISCONNECTED';
        eventSource = null;
        ElMessage.error("实时消息通道连接已断开");
    } else {
        ElMessage.warning("实时消息通道连接中断，尝试重连...");
    }
  };
};

const disconnectSSE = () => {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
    console.log("SSE 连接已关闭");
  }
  connectionStatus.value = 'DISCONNECTED';
};

const formatTimestamp = (timestamp) => {
  if (!timestamp) return '';
  return new Date(timestamp).toLocaleString(); // 使用本地化时间格式
};

// 辅助函数：截断过长的主题名，用于显示
const truncateTopic = (topic, maxLength = 30) => {
    if (!topic) return '';
    if (topic.length <= maxLength) return topic;
    return topic.substring(0, maxLength - 3) + '...';
}

onMounted(connectSSE);
onUnmounted(disconnectSSE);

</script>

<style scoped>
/* 样式基本保持不变 */
.message-display-area {
  height: 300px; /* 或根据需要调整 */
  overflow-y: auto;
  border: 1px solid var(--el-border-color);
  padding: 10px;
  font-size: 13px;
  line-height: 1.6;
  background-color: #f9f9f9;
}
.message-item {
  margin-bottom: 8px;
  border-bottom: 1px dashed #eee;
  padding-bottom: 5px;
  white-space: pre-wrap;
  word-wrap: break-word;
}
.topic {
  font-weight: bold;
  color: #409eff;
  margin-right: 10px;
  cursor: pointer; /* 添加手型光标提示可以看完整 topic */
}
.payload {
    display: inline-block;
    max-width: 70%;  /* 添加限制宽度防止溢出 */
}
.timestamp {
    float: right;
    color: #999;
    font-size: 12px;
    margin-left: 10px;
    white-space: nowrap;
}
.no-messages {
  color: #909399;
  text-align: center;
  padding-top: 20px;
}
.message-item::after {
    content: "";
    display: table;
    clear: both;
}
.el-tag {
    float: right;  /* 移到 header 中 */
}
</style>