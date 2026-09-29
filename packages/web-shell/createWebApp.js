import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import Shell from './Shell.vue'
import UiPreview from './UiPreview.vue'
import './shell.css'

export function mountWebApp({ title, pages, home }) {
  const routes = Object.entries(pages).map(([file, component]) => {
    const path = file.replace(/^\.\/pages\//, '/pages/').replace(/\.vue$/, '')
    return { path, component, meta: { label: path.split('/').at(-1) } }
  })
  routes.unshift({ path: '/', redirect: home })
  if (import.meta.env.DEV) routes.push({ path: '/ui', component: UiPreview, meta: { label: 'UI 状态' } })

  const router = createRouter({ history: createWebHistory(), routes })
  createApp(Shell, { title, routes: routes.slice(1) }).use(router).mount('#app')
}
