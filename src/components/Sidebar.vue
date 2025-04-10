<template>
    <el-aside width="200px" style="background-color: #545c64;">
      <el-scrollbar>
        <el-menu
          :default-openeds="['1']"
          :default-active="$route.path"
          background-color="#545c64"
          text-color="#fff"
          active-text-color="#ffd04b"
          router
        >
          <el-menu-item index="/dashboard" v-if="showDashboard"> <!-- Optional -->
            <el-icon><DataLine /></el-icon>
            <span>仪表盘</span>
          </el-menu-item>
           <el-menu-item index="/devices">
            <el-icon><Cpu /></el-icon>
            <span>设备管理</span>
          </el-menu-item>
          <el-menu-item index="/mqtt">
            <el-icon><ChatDotRound /></el-icon>
            <span>MQTT 控制</span>
          </el-menu-item>
           <!-- Add more items as needed -->
        </el-menu>
      </el-scrollbar>
    </el-aside>
  </template>
  
  <script setup>
  import { DataLine, Cpu, ChatDotRound } from '@element-plus/icons-vue';
  import { ref } from 'vue';
  
  // Feature flag for dashboard
  const showDashboard = ref(true); // Set to false if you don't implement a dashboard
  </script>
  
  <style scoped>
  .el-aside {
    color: var(--el-text-color-primary);
    background: #fff !important;
    border-right: solid 1px var(--el-border-color);
    height: 100%;
  }
  .el-menu {
    border-right: none;
  }
  </style>