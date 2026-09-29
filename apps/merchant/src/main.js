import { mountWebApp } from '../../../packages/web-shell/createWebApp.js'

mountWebApp({
  title: '商家端',
  pages: import.meta.glob('./pages/**/*.vue'),
  home: '/pages/dashboard',
})
