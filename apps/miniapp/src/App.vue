<script>
import { apiRuntime } from './services/api-runtime.js'

export default {
  onLaunch() {
    // 云托管模式下先初始化云环境：此后接口经 callContainer 调用，无需配置通讯域名。
    apiRuntime.initCloud()
  },
}
</script>

<style>
/* Shared page classes must live in app.wxss: repeated page style-src imports
   are deduplicated by the WeChat build and disappear from later pages. */
@import './styles/reservations.css';

page {
  background: #f2f3f5;
  color: #1d2129;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif;
}
</style>
