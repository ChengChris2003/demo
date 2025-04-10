<template>
    <el-container class="layout-container">
      <Sidebar />
      <el-container direction="vertical">
        <!-- Optional Header -->
        <!-- <el-header>Header Content</el-header> -->
        <el-main>
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </el-main>
      </el-container>
    </el-container>
  </template>
  
  <script setup>
  import Sidebar from '@/components/Sidebar.vue';
  </script>
  
  <style scoped>
  .layout-container {
    height: 100vh;
  }
  .el-main {
    padding: 20px;
    background-color: #f4f4f5; /* Light gray background for content */
  }
  /* Basic fade transition */
  .fade-enter-active, .fade-leave-active {
    transition: opacity 0.3s ease;
  }
  .fade-enter-from, .fade-leave-to {
    opacity: 0;
  }
  </style>