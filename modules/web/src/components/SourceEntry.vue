<template>
  <div class="source-entry">
    <div class="entry-card" @click="store.createSource()">
      <div class="entry-title">新建书源</div>
      <div class="entry-desc">声明式规则（搜索/详情/目录/正文分栏填写）</div>
    </div>
    <div class="entry-card" @click="createJsSource">
      <div class="entry-title">新建 JS 源</div>
      <div class="entry-desc">单文件脚本（config 配置 + search/getChapters/getContent 函数）</div>
    </div>
    <div class="entry-tip">从右侧「源列表」点编辑按钮可修改已有源</div>
  </div>
</template>

<script setup lang="ts">
import { useSourceStore } from '@/store'

const store = useSourceStore()

const createJsSource = async () => {
  try {
    await store.createJsSource()
  } catch (e) {
    ElMessage({
      message: (e as Error).message || 'JS 源模板加载失败',
      type: 'error',
    })
  }
}
</script>

<style lang="scss" scoped>
.source-entry {
  height: calc(100vh - 5px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}
.entry-card {
  width: 320px;
  padding: 20px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  cursor: pointer;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
  &:hover {
    border-color: var(--el-color-primary);
    box-shadow: 0 2px 12px var(--el-color-primary-light-7);
  }
  .entry-title {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 6px;
  }
  .entry-desc {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.entry-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
