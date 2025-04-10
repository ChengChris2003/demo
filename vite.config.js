// vite.config.js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 1. 导入 Node.js 的 path 模块用于路径解析 (旧方法，仍可用)
// import path from 'path'

// 或者使用更现代的 URL API (推荐在 ESM 环境下使用)
import { fileURLToPath, URL } from 'node:url'

// 2. (如果你安装了 Element Plus 按需导入插件) 导入相关插件和解析器
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),

    // 3. (如果你安装了 Element Plus 按需导入插件) 配置 Element Plus 按需自动导入
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    alias: {
      // 4. 配置 '@' 路径别名，指向 'src' 目录
      // 使用 URL API 的方法 (推荐):
      '@': fileURLToPath(new URL('./src', import.meta.url))

      // 使用 path 模块的方法 (旧方法):
      // '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    // 5. (可选) 配置开发服务器
    port: 5173, // 可以指定一个固定的端口，避免每次启动都变化
    // host: '0.0.0.0', // 如果需要让局域网内其他设备访问
    // proxy: { // 如果需要配置 API 代理，解决跨域问题
    //   '/api': { // 将所有 /api 开头的请求代理到后端
    //     target: 'http://localhost:8080', // 你的后端 API 地址
    //     changeOrigin: true,
    //     rewrite: (path) => path.replace(/^\/api/, '') // 可选：移除请求路径中的 /api 前缀
    //   }
    // }
  }
})