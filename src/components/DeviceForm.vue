<template>
    <el-dialog
      :model-value="visible"
      :title="mode === 'add' ? '添加设备' : '编辑设备'"
      width="500px"
      @update:model-value="$emit('update:visible', $event)"
      @close="handleClose"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" v-loading="formLoading">
        <el-form-item label="设备名称" prop="deviceName">
          <el-input v-model="form.deviceName" placeholder="请输入设备名称"></el-input>
        </el-form-item>
        <el-form-item label="设备类型" prop="deviceType">
          <el-input v-model="form.deviceType" placeholder="请输入设备类型"></el-input>
        </el-form-item>
        <el-form-item label="状态" prop="status">
           <el-input v-model="form.status" placeholder="请输入设备状态 (e.g., 在线, 离线)"></el-input>
           <!-- Or use el-select for predefined statuses -->
           <!--
           <el-select v-model="form.status" placeholder="请选择状态">
              <el-option label="在线" value="在线"></el-option>
              <el-option label="离线" value="离线"></el-option>
              <el-option label="故障" value="故障"></el-option>
           </el-select>
           -->
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="$emit('update:visible', false)">取消</el-button>
          <el-button type="primary" @click="submitForm" :loading="formLoading">确定</el-button>
        </span>
      </template>
    </el-dialog>
  </template>
  
  <script setup>
  import { ref, reactive, watch, nextTick } from 'vue';
  import { ElMessage } from 'element-plus';
  import api from '@/services/api';
  
  const props = defineProps({
    visible: Boolean,
    deviceData: Object, // Pass device data for editing
    mode: {
      type: String,
      default: 'add', // 'add' or 'edit'
    },
  });
  
  const emit = defineEmits(['update:visible', 'submit']);
  
  const formRef = ref(null);
  const formLoading = ref(false);
  const form = reactive({
    id: null,
    deviceName: '',
    deviceType: '',
    status: '',
  });
  
  const rules = reactive({
    deviceName: [{ required: true, message: '请输入设备名称', trigger: 'blur' }],
    deviceType: [{ required: true, message: '请输入设备类型', trigger: 'blur' }],
    // Add more rules if needed
  });
  
  // Watch for changes in deviceData to populate the form for editing
  watch(() => props.deviceData, (newData) => {
    if (newData && props.mode === 'edit') {
      form.id = newData.id;
      form.deviceName = newData.deviceName;
      form.deviceType = newData.deviceType;
      form.status = newData.status;
    } else {
      // Reset form for adding or when dialog closes without data
      resetForm();
    }
  });
  
  // Also reset form when visibility changes to true (opening dialog)
  watch(() => props.visible, (newVal) => {
      if (newVal && props.mode === 'add') {
          // Use nextTick to ensure formRef is available after dialog opens
          nextTick(() => {
               resetForm();
               formRef.value?.clearValidate(); // Clear validation state
          });
      } else if (newVal && props.mode === 'edit' && props.deviceData) {
           nextTick(() => {
               form.id = props.deviceData.id;
               form.deviceName = props.deviceData.deviceName;
               form.deviceType = props.deviceData.deviceType;
               form.status = props.deviceData.status;
               formRef.value?.clearValidate(); // Clear validation state
          });
      }
  });
  
  
  const resetForm = () => {
      form.id = null;
      form.deviceName = '';
      form.deviceType = '';
      form.status = '';
  };
  
  
  const handleClose = () => {
    // Optional: reset form on close if needed, handled by watch now
    // resetForm();
    // formRef.value?.clearValidate();
  };
  
  const submitForm = async () => {
    if (!formRef.value) return;
    await formRef.value.validate(async (valid) => {
      if (valid) {
        formLoading.value = true;
        try {
          const payload = { ...form }; // Create payload from form data
          if (props.mode === 'add') {
            await api.registerDevice(payload);
            ElMessage({ type: 'success', message: '设备添加成功!' });
          } else {
            await api.updateDevice(form.id, payload);
            ElMessage({ type: 'success', message: '设备更新成功!' });
          }
          emit('submit'); // Notify parent to refresh
          emit('update:visible', false); // Close dialog
        } catch (error) {
          console.error("Form submission error:", error);
          // Error message shown by interceptor
        } finally {
          formLoading.value = false;
        }
      } else {
        console.log('Form validation failed!');
        return false;
      }
    });
  };
  </script>