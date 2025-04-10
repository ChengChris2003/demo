import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { createPinia } from 'pinia' // If using Pinia
import './assets/main.css' // Optional global styles

const app = createApp(App)
const pinia = createPinia() // If using Pinia

app.use(router)
app.use(pinia) // If using Pinia
app.use(ElementPlus)

app.mount('#app')