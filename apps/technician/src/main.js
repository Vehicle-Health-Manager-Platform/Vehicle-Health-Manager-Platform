import { mountWebApp } from '../../../packages/web-shell/createWebApp.js'

mountWebApp({
  title: '技师端',
  pages: import.meta.glob('./pages/**/*.vue'),
  home: '/pages/dashboard',
})
