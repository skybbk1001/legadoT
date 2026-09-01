<template>
  <div class="editor">
    <source-entry v-if="showEntry" class="left" />
    <js-source-editor v-else-if="isJsSource" class="left" />
    <source-tab-form v-else class="left" :config="config" />
    <tool-bar />
    <source-tab-tools class="right" />
  </div>
</template>
<script setup lang="ts">
import bookSourceConfig from '@/config/bookSourceEditConfig'
import rssSourceConfig from '@/config/rssSourceEditConfig'
import '@/assets/sourceeditor.css'
import { useDark } from '@vueuse/core'
import type { SourceConfig } from '@/config/sourceConfig'
import { useSourceStore } from '@/store'

useDark()

const store = useSourceStore()

let config: SourceConfig

const isBookSourcePage = /bookSource/i.test(location.href)
if (isBookSourcePage) {
  config = bookSourceConfig as SourceConfig
  document.title = '书源管理'
} else {
  config = rssSourceConfig as SourceConfig
  document.title = '订阅源管理'
}

/** 书源页未进入编辑时显示新建入口；订阅源页保持原表单 */
const showEntry = computed(() => isBookSourcePage && !store.editing)

/** 本次编辑会话按进入时是否 JS 源定格：编辑中清空脚本不应切回声明式表单 */
const isJsSource = computed(() => isBookSourcePage && store.editingJsSource)
</script>
<style lang="scss" scoped>
.editor {
  display: flex;
  height: 100vh;
  overflow: hidden;
  .left {
    flex: 1;
    margin-left: 20px;
  }
  .right {
    flex: 1;
    width: 360px;
    margin-right: 20px;
  }
}
</style>
