<template>
  <el-input
    v-model="searchKey"
    class="search"
    :prefix-icon="Search"
    placeholder="筛选源"
  />
  <div class="tool">
    <el-button @click="importSourceFile" :icon="Folder">打开</el-button>
    <el-button
      :disabled="sourcesFiltered.length === 0"
      @click="outExport"
      :icon="Download"
    >
      导出</el-button
    >
    <el-button
      type="danger"
      :icon="Delete"
      @click="deleteSelectSources"
      :disabled="sourceSelect.length === 0"
      >删除</el-button
    >
    <el-button
      type="danger"
      :icon="Delete"
      @click="clearAllSources"
      :disabled="sources.length === 0"
      >清空</el-button
    >
  </div>
  <el-checkbox-group id="source-list" v-model="sourceUrlSelect">
    <virtual-list
      style="height: 100%; overflow-y: auto; overflow-x: hidden"
      :data-key="(source: Source) => getSourceName(source)"
      :data-sources="sourcesFiltered"
      :data-component="SourceItem"
      :estimate-size="45"
    />
  </el-checkbox-group>
</template>

<script setup lang="ts">
import API from '@api'
import { Folder, Delete, Download, Search } from '@element-plus/icons-vue'
import {
  isSourceMatches,
  isJsBookSource,
  getSourceUniqueKey,
  getSourceName,
  convertSourcesToMap,
} from '@utils/souce'
import VirtualList from 'vue3-virtual-scroll-list'
import SourceItem from './SourceItem.vue'
import type { Source } from '@/source'

const store = useSourceStore()
const sourceUrlSelect = ref<string[]>([])
const searchKey = ref('')
const sources = computed(() => store.sources)

/* 筛选源 */
const sourcesFiltered = computed<Source[]>(() => {
  const key = searchKey.value
  if (key === '') return sources.value
  return sources.value.filter(source => isSourceMatches(source, key))
})
// 计算当前筛选关键词下的选中源
const sourceSelect = computed<Source[]>(() => {
  const urls = sourceUrlSelect.value
  if (urls.length == 0) return []
  const sourcesFilteredMap =
    searchKey.value == ''
      ? store.sourcesMap
      : convertSourcesToMap(sourcesFiltered.value)
  return urls.reduce((sources, sourceUrl) => {
    const source = sourcesFilteredMap.get(sourceUrl)
    if (source) sources.push(source)
    return sources
  }, [] as Source[])
})

const deleteSelectSources = () => {
  const sourceSelectValue = sourceSelect.value
  API.deleteSource(sourceSelectValue).then(({ data }) => {
    if (!data.isSuccess) return ElMessage.error(data.errorMsg)
    store.deleteSources(sourceSelectValue)
    const sourceUrlSelectRawValue = toRaw(sourceUrlSelect.value)
    sourceSelectValue.forEach(source => {
      const index = sourceUrlSelectRawValue.indexOf(getSourceUniqueKey(source))
      if (index > -1) sourceUrlSelectRawValue.splice(index, 1)
    })
    sourceUrlSelect.value = sourceUrlSelectRawValue
  })
}
const clearAllSources = () => {
  store.clearAllSource()
  sourceUrlSelect.value = []
}

//导入本地文件
const importSourceFile = () => {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json,.txt,.js'
  input.addEventListener('change', () => {
    const files = input.files
    if (files === null) {
      return ElMessage.info('未选择文件')
    }
    const file = files[0]
    const reader = new FileReader()
    reader.readAsText(file)
    reader.onload = () => {
      const text = reader.result as string
      if (file.name.toLowerCase().endsWith('.js')) {
        return importJsSource(text)
      }
      try {
        const jsonData = JSON.parse(text)
        store.saveSources(jsonData)
      } catch (e: unknown) {
        ElMessage.error('上传的源格式错误: ' + (e as Error).message)
      }
    }
  })
  input.click()
}

/** 导入 .js 单文件：直接按脚本原文保存到 App（extract 校验），成功则并入列表 */
const importJsSource = async (text: string) => {
  try {
    const { data } = await API.saveJsSource(text)
    if (data.isSuccess) {
      store.addSource(data.data)
      ElMessage({
        message: `JS源《${data.data.bookSourceName}》已导入并保存到「阅读3.0APP」`,
        type: 'success',
      })
    } else {
      ElMessage.error(`JS源导入失败: ${data.errorMsg}`)
    }
  } catch (e: unknown) {
    ElMessage.error('JS源导入失败: ' + (e as Error).message)
  }
}

const isBookSource = /bookSource/i.test(window.location.href)

const downloadText = (fileName: string, text: string, type: string) => {
  const exportFile = document.createElement('a')
  exportFile.download = fileName
  exportFile.href = window.URL.createObjectURL(new Blob([text], { type }))
  exportFile.click()
  window.URL.revokeObjectURL(exportFile.href) //avoid memory leak
}

const outExport = () => {
  const sources =
    sourceUrlSelect.value.length === 0
      ? sourcesFiltered.value
      : sourceSelect.value
  // JS 源逐个导出 .js 单文件（社区分享形态），其余维持 JSON 合集
  const jsSources = isBookSource ? sources.filter(isJsBookSource) : []
  const otherSources = sources.filter(s => !isJsBookSource(s))

  jsSources.forEach(source => {
    const fileName =
      source.bookSourceName.replace(/[\\/:*?"<>|]/g, '_') + '.js'
    downloadText(fileName, source.mainJs!, 'text/javascript')
  })

  if (otherSources.length > 0) {
    const sourceType = isBookSource ? 'BookSource' : 'RssSource'
    downloadText(
      `${sourceType}_${Date()
        .replace(/.*?\s(\d+)\s(\d+)\s(\d+:\d+:\d+).*/, '$2$1$3')
        .replace(/:/g, '')}.json`,
      JSON.stringify(otherSources, null, 4),
      'application/json',
    )
  }
  if (jsSources.length > 0) {
    ElMessage.info(`已导出 ${jsSources.length} 个 JS 源单文件`)
  }
}
</script>

<style lang="scss" scoped>
.tool {
  display: flex;
  margin: 4px 0;
  justify-content: center;
}

#source-list {
  margin-top: 6px;
  height: calc(100vh - 112px - 7px);
  :deep(.el-checkbox) {
    margin-bottom: 4px;
    width: 100%;
  }
}
</style>
