<script setup>
defineProps({
  title: { type: String, required: true },
  routes: { type: Array, required: true },
})
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="app-brand">
        <span class="brand-mark" aria-hidden="true">车</span>
        <div>
          <strong>汽车健康管家</strong>
          <small>{{ title }}</small>
        </div>
      </div>
      <span class="phase-tag">S0 工程骨架</span>
    </header>
    <div class="app-layout">
      <nav class="app-nav" aria-label="页面导航">
        <RouterLink v-for="route in routes" :key="route.path" :to="route.path">
          {{ route.path.replace('/pages/', '').replaceAll('/', ' / ') }}
        </RouterLink>
      </nav>
      <main class="app-main">
        <p class="eyebrow">{{ title }} / 页面占位</p>
        <h1>{{ $route.path.replace('/pages/', '').replaceAll('/', ' / ') }}</h1>
        <p>页面路由已就绪，业务功能将在对应阶段实现。</p>
        <div class="page-placeholder"><RouterView /></div>
      </main>
    </div>
  </div>
</template>
