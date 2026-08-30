<template>
  <div class="js-source-editor">
    <el-alert type="info" :closable="false" class="tip">
      <template #title>
        JS 源：下方编辑完整脚本。名称/地址/分组等元数据由脚本内 config
        声明，保存时由 App 重新提取（enabled 等用户态保留）。
      </template>
    </el-alert>
    <el-input
      id="js-source-main"
      v-model="mainJs"
      type="textarea"
      spellcheck="false"
      placeholder="粘贴或编写 JS 源脚本（须含顶层 config 对象与 search/getChapters/getContent 函数）"
    />
  </div>
</template>

<script setup lang="ts">
import { useSourceStore } from '@/store'
import type { BookSoure } from '@/source'

const store = useSourceStore()

const mainJs = computed({
  get: () => (store.currentSource as BookSoure).mainJs ?? '',
  set: val => ((store.currentSource as BookSoure).mainJs = val),
})
</script>

<style lang="scss" scoped>
.js-source-editor {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 5px);
  .tip {
    margin-bottom: 4px;
  }
  :deep(.el-textarea) {
    flex: 1;
  }
  :deep(#js-source-main) {
    height: 100%;
    overflow-y: auto;
    font-family: 'Consolas', 'Courier New', monospace;
    font-size: 13px;
    line-height: 1.5;
    white-space: pre;
  }
}
</style>
