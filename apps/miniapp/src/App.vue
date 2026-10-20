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
@import './styles/design-system.css';
@import './styles/reservations.css';

page {
  /* 工业实用 × 仪表盘语义：暖纸灰底、暖近黑文字，避免纯白压纯黑。 */
  background: #f3f1eb;
  color: #1a1d18;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'HarmonyOS Sans SC', sans-serif;
  letter-spacing: 0.2rpx;
}
</style>
