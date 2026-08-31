<template>
  <div class="menu flex-column-center">
    <template v-for="button in buttons" :key="button.name">
      <el-button
        v-if="!button.bookSourceOnly || isBookSourcePage"
        size="large"
        @click="button.action"
      >
        {{ button.name }}
      </el-button>
    </template>
    <el-button size="large" @click="() => (hotkeysDialogVisible = true)"
      >快捷键</el-button
    >
  </div>
  <el-dialog
    v-model="hotkeysDialogVisible"
    :show-close="false"
    :before-close="stopRecordKeyDown"
  >
    <template #header="{ titleClass, titleId }">
      <div class="hotkeys-header flex-space-between">
        <div :id="titleId" :class="titleClass">
          快捷键设置
          <span v-if="recordKeyDowning">
            <el-text> / 录入中 </el-text>
          </span>
        </div>
        <el-button
          :disabled="recordKeyDowning"
          @click="saveHotKeys"
          :icon="CircleCheckFilled"
          >保存</el-button
        >
      </div>
    </template>

    <div class="hotkeys-settings flex-column-center">
      <div
        v-for="(button, buttonIndex) in buttons"
        :key="button.name"
        class="hotkeys-item flex-space-between"
      >
        <span class="title"
          ><el-text>{{ button.name }}</el-text></span
        >
        <div class="hotkeys-item__content">
          <div v-for="(key, hotKeysIndex) in button.hotKeys" :key="key">
            <kbd>{{ key }}</kbd>
            <span v-if="hotKeysIndex + 1 < button.hotKeys.length">
              <el-text>+</el-text>
            </span>
          </div>
          <span v-if="button.hotKeys.length == 0">未设置</span>
        </div>
        <el-button
          :disabled="recordKeyDowning"
          text
          :icon="Edit"
          @click="recordKeyDown(buttonIndex)"
          >编辑</el-button
        >
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import API from '@api'
import { CircleCheckFilled, Edit } from '@element-plus/icons-vue'
import hotkeys from 'hotkeys-js'
import {
  getSourceName,
  getSourceUniqueKey,
  isInvaildSource,
  isJsBookSource,
  normalizeSource,
} from '../utils/souce'
import type { BookSoure, Source } from '@/source'

const isBookSourcePage = /bookSource/i.test(location.href)

const store = useSourceStore()
const pull = () => {
  const loadingMsg = ElMessage({
    message: '加载中……',
    showClose: true,
    duration: 0,
  })
  API.getSources()
    .then(({ data }) => {
      if (data.isSuccess) {
        store.changeTabName('editList')
        store.saveSources(data.data)
        ElMessage({
          message: `成功拉取${data.data.length}条源`,
          type: 'success',
        })
      } else {
        ElMessage({
          message: data.errorMsg ?? '后端错误',
          type: 'error',
        })
      }
    })
    .finally(() => loadingMsg.close())
}

/**
 * 批量推送：JS 源逐条走 saveJsSource（脚本原文 + App 端 extract 校验/提取，
 * 避免 JSON 路径绕过校验导致"新脚本+旧元数据"混合入库）；
 * 声明式源维持 saveBookSources 批量端点。
 */
const push = async () => {
  const sources = [...store.sources]
  store.changeTabName('editList')
  if (sources.length === 0) {
    return ElMessage({
      message: '空空如也',
      type: 'info',
    })
  }
  ElMessage({
    message: '正在推送中',
    type: 'info',
  })
  const jsSources = sources.filter(isJsBookSource)
  const declarativeSources = sources.filter(s => !isJsBookSource(s))
  const okSources: Source[] = []
  let failCount = 0

  if (declarativeSources.length > 0) {
    const { data } = await API.saveSources(declarativeSources)
    if (data.isSuccess && Array.isArray(data.data)) {
      okSources.push(...data.data)
      failCount += declarativeSources.length - data.data.length
    } else {
      failCount += declarativeSources.length
    }
  }

  for (const source of jsSources) {
    try {
      const { data } = await API.saveJsSource(source.mainJs!)
      if (data.isSuccess) {
        okSources.push(data.data)
        // extract 后元数据/URL 可能变化，用提取结果替换列表项
        store.updateSource(getSourceUniqueKey(source), data.data)
      } else {
        failCount++
      }
    } catch {
      failCount++
    }
  }

  if (failCount > 0) {
    store.setPushReturnSources(okSources)
  }
  ElMessage({
    message: `批量推送源到「阅读3.0APP」\n共计: ${sources.length} 条\n成功: ${okSources.length} 条\n失败: ${failCount} 条${failCount > 0 ? '\n推送失败的源将用红色字体标注!' : ''}`,
    type: failCount > 0 ? 'warning' : 'success',
  })
}

const conver2Tab = () => {
  store.changeTabName('editTab')
  store.changeEditTabSource(store.currentSource)
}
const conver2Source = () => {
  store.changeCurrentSource(store.editTabSource)
}

const undo = () => {
  store.editHistoryUndo()
}

const clearEdit = () => {
  store.clearEdit()
  ElMessage({
    message: '已清除',
    type: 'success',
  })
}

const redo = () => {
  store.editHistoryRedo()
}

const saveSource = () => {
  const source = store.currentSource
  if (isJsBookSource(source)) {
    return saveJsSource(source)
  }
  if (isInvaildSource(source)) {
    normalizeSource(source)
    API.saveSource(source).then(({ data }) => {
      const sourceName = getSourceName(source)
      if (data.isSuccess) {
        ElMessage({
          message: `源《${sourceName}》已成功保存到「阅读3.0APP」`,
          type: 'success',
        })
        //save to store
        store.saveCurrentSource()
      } else {
        ElMessage({
          message: `源《${sourceName}》保存失败!\nErrorMsg: ${data.errorMsg}`,
          type: 'error',
        })
      }
    })
  } else {
    ElMessage({
      message: `请检查<必填>项是否全部填写`,
      type: 'error',
    })
  }
}

/** JS 源保存：提交脚本原文，用 App 提取结果回填（脚本改名/改地址也能反映） */
const saveJsSource = (source: BookSoure) => {
  const js = source.mainJs!
  const oldUrl = source.bookSourceUrl
  API.saveJsSource(js).then(({ data }) => {
    if (data.isSuccess) {
      const saved = data.data
      ElMessage({
        message: `JS源《${saved.bookSourceName}》已成功保存到「阅读3.0APP」`,
        type: 'success',
      })
      store.changeCurrentSource(saved)
      store.saveCurrentSource()
      // 脚本改了 bookSourceUrl 时移除列表里的旧键，避免残留旧源
      if (oldUrl && oldUrl !== saved.bookSourceUrl) {
        const stale = store.sourcesMap.get(oldUrl)
        if (stale) store.deleteSources([stale])
      }
    } else {
      ElMessage({
        message: `JS源保存失败!\nErrorMsg: ${data.errorMsg}`,
        type: 'error',
      })
    }
  })
}

const debug = () => {
  store.startDebug()
}

/** 返回新建入口页（新建书源/新建 JS 源 二选一） */
const backToEntry = () => {
  store.clearEdit()
}

interface ToolButton {
  name: string
  hotKeys: string[]
  action: () => void
  /** 仅书源页显示（订阅源页隐藏，不占热键位序） */
  bookSourceOnly?: boolean
}

const buttons = ref<ToolButton[]>(
  Array.of(
    { name: '⇈推送源', hotKeys: [], action: push },
    { name: '⇊拉取源', hotKeys: [], action: pull },
    { name: '⋙生成源', hotKeys: [], action: conver2Tab },
    { name: '⋘编辑源', hotKeys: [], action: conver2Source },
    { name: '✗清空表单', hotKeys: [], action: clearEdit },
    { name: '↶撤销操作', hotKeys: [], action: undo },
    { name: '↷重做操作', hotKeys: [], action: redo },
    { name: '⇏调试源', hotKeys: [], action: debug },
    { name: '✓保存源', hotKeys: [], action: saveSource },
    // 追加在末尾：不影响 localStorage 里既有快捷键的位序映射
    { name: '＋新建源', hotKeys: [], action: backToEntry, bookSourceOnly: true },
  ),
)
const hotkeysDialogVisible = ref(true)

const recordKeyDowning = ref(false)

const recordKeyDownIndex = ref(-1)

const stopRecordKeyDown = () => {
  if (!recordKeyDowning.value) {
    hotkeysDialogVisible.value = false
  }
  recordKeyDowning.value = false
}

watch(
  hotkeysDialogVisible,
  visibale => {
    if (!visibale) {
      hotkeys.unbind('*')
      readHotkeysConfig()
      bindHotKeys()
      return
    }
    readHotkeysConfig()
    hotkeys.unbind()
    /**监听按键 */
    hotkeys('*', event => {
      event.preventDefault()
      const pressedKeys = hotkeys.getPressedKeyString()
      if (pressedKeys.length == 1 && pressedKeys[0] == 'esc') {
        //单独按下esc 不录入
        return
      }
      if (recordKeyDowning.value && recordKeyDownIndex.value > -1)
        buttons.value[recordKeyDownIndex.value].hotKeys = pressedKeys
    })
  },
  { immediate: true },
)

const recordKeyDown = (index: number) => {
  recordKeyDowning.value = true
  ElMessage({
    message: '按ESC键或者点击空白处结束录入',
    type: 'info',
  })
  buttons.value[index].hotKeys = []
  recordKeyDownIndex.value = index
}

const saveHotKeys = () => {
  const hotKeysConfig: string[][] = []
  buttons.value.forEach(({ hotKeys }) => {
    hotKeysConfig.push(hotKeys)
  })
  saveHotkeysConfig(hotKeysConfig)
  hotkeysDialogVisible.value = false
}

const bindHotKeys = () => {
  // hotkeys默认过滤INPUT SELECT TEXTAREA
  hotkeys.filter = () => true
  buttons.value.forEach(({ hotKeys, action }) => {
    if (hotKeys.length == 0) return
    hotkeys(hotKeys.join('+'), event => {
      event.preventDefault()
      action.call(null)
    })
  })
}
const saveHotkeysConfig = (config: string[][]) => {
  localStorage.setItem('legado_web_hotkeys', JSON.stringify(config))
}

/**
 * 读取快捷键配置
 * @return 是否成功读取配置
 */
function readHotkeysConfig() {
  try {
    const localStorageConfig = localStorage.getItem('legado_web_hotkeys')
    if (localStorageConfig === null) return false
    const config = JSON.parse(localStorageConfig)
    if (!Array.isArray(config) || config.length == 0) return false
    // 按钮集可能新增（如 ＋新建源），旧配置长度不足时补空数组
    buttons.value.forEach(
      (button, index) => (button.hotKeys = config[index] ?? []),
    )
    return true
  } catch {
    ElMessage({ message: '快捷键配置错误', type: 'error' })
    localStorage.removeItem('legado_web_hotkeys')
  }
  return false
}

onMounted(() => {
  /**读取热键配置 */
  if (readHotkeysConfig()) {
    hotkeysDialogVisible.value = false
  }
})
</script>

<style lang="scss" scoped>
.flex-space-between {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.flex-column-center {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.menu > .el-button {
  margin: 4px;
  padding: 1em;
  width: 6em;
}

.hotkeys-item {
  .title {
    width: 5em;
    display: flex;
    justify-content: flex-end;
    margin-right: 1em;
  }
  .hotkeys-item__content {
    display: flex;
    flex-wrap: wrap;
    flex: 1;
    div {
      margin-bottom: 1em;
    }
    span {
      margin: 0.5em;
    }
  }
}
</style>
