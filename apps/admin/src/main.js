import { mountWebApp } from '../../../packages/web-shell/createWebApp.js'

mountWebApp({
  title: '运营端',
  pages: import.meta.glob('./pages/**/*.vue'),
  home: '/pages/dashboard',
})
