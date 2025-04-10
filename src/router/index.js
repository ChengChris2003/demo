import { createRouter, createWebHistory } from 'vue-router';
import DefaultLayout from '@/layouts/DefaultLayout.vue';

const routes = [
  {
    path: '/',
    component: DefaultLayout,
    redirect: '/devices', // Or '/dashboard' if you have one
    children: [
      {
        path: 'dashboard', // Optional
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'), // Lazy load
        meta: { title: '仪表盘' }
      },
      {
        path: 'devices',
        name: 'DeviceManagement',
        component: () => import('@/views/DeviceManagement.vue'), // Lazy load
        meta: { title: '设备管理' }
      },
      {
        path: 'mqtt',
        name: 'MqttControl',
        component: () => import('@/views/MqttControl.vue'), // Lazy load
        meta: { title: 'MQTT 控制' }
      },
    ],
  },
  // Add other routes if needed (e.g., login page outside the default layout)
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
});

// Optional: Add navigation guard to update page title
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `设备管理平台 - ${to.meta.title}` : '设备管理平台';
  next();
});


export default router;