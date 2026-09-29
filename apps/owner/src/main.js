import { mountWebApp } from '../../../packages/web-shell/createWebApp.js'

mountWebApp({
  title: '车主端',
  pages: import.meta.glob('./pages/**/*.vue'),
  home: '/pages/home/index',
})
