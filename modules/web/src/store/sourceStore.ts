import { defineStore } from 'pinia'
import {
  emptyBookSource,
  emptyRssSource,
  getSourceUniqueKey,
  convertSourcesToMap,
  fetchJsSourceTemplate,
  isJsBookSource,
} from '@utils/souce'
import type { BookSoure, RssSource, Source } from '@/source'

const isBookSource = /bookSource/i.test(location.href)
const emptySource = isBookSource ? emptyBookSource : emptyRssSource

export const useSourceStore = defineStore('source', {
  state: () => {
    return {
      bookSources: shallowRef([] as BookSoure[]), // 临时存放所有书源,
      rssSources: shallowRef([] as RssSource[]), // 临时存放所有订阅源
      savedSources: [] as Source[], // 批量保存到阅读app成功的源
      currentSource: JSON.parse(JSON.stringify(emptySource)) as Source, // 当前编辑的源
      editing: false, // 是否已进入编辑界面（false 时书源页左侧显示新建入口）
      editingJsSource: false, // 本次编辑会话是否为 JS 源（进入编辑时定格，脚本清空不再回落表单）
      currentTab: localStorage.getItem('tabName') || 'editTab',
      editTabSource: {} as Source, // 生成序列化的json数据
      isDebuging: false,
    }
  },
  getters: {
    sources: (state): Source[] =>
      isBookSource ? state.bookSources : state.rssSources,
    sourcesMap: function (): Map<string, Source> {
      return convertSourcesToMap(this.sources)
    },
    savedSourcesMap: (state): Map<string, Source> =>
      convertSourcesToMap(state.savedSources),
    currentSourceUrl: state =>
      isBookSource
        ? (state.currentSource as BookSoure).bookSourceUrl
        : (state.currentSource as RssSource).sourceUrl,
    searchKey: (state): string =>
      isBookSource
        ? (state.currentSource as BookSoure)?.ruleSearch?.checkKeyWord || '我的'
        : '',
  },
  actions: {
    startDebug() {
      this.currentTab = 'editDebug'
      this.isDebuging = true
    },
    debugFinish() {
      this.isDebuging = false
    },

    //拉取源后保存
    saveSources(data: Source[]) {
      if (isBookSource) {
        this.bookSources = markRaw(data) as BookSoure[]
      } else {
        this.rssSources = markRaw(data) as RssSource[]
      }
    },
    //批量推送
    setPushReturnSources(returnSoures: Source[]) {
      this.savedSources = returnSoures
    },
    //删除源
    deleteSources(data: Source[]) {
      const sources: Source[] = isBookSource
        ? this.bookSources
        : this.rssSources
      data.forEach(source => {
        const index = sources.indexOf(source)
        if (index > -1) sources.splice(index, 1)
      })
    },
    //保存当前编辑源
    saveCurrentSource() {
      const source = this.currentSource,
        map = this.sourcesMap
      map.set(getSourceUniqueKey(source), JSON.parse(JSON.stringify(source)))
      this.saveSources(Array.from(map.values()))
    },
    /** 按旧键替换列表项（JS 源 extract 后元数据/URL 可能变化） */
    updateSource(oldKey: string, source: Source) {
      const list = this.sources
      const index = list.findIndex(s => getSourceUniqueKey(s) === oldKey)
      if (index === -1) return
      const next = [...list]
      next[index] = source
      this.saveSources(next)
    },
    /** 按键插入/覆盖列表项（导入单源用） */
    addSource(source: Source) {
      const map = this.sourcesMap
      map.set(getSourceUniqueKey(source), source)
      this.saveSources(Array.from(map.values()))
    },
    // 更改当前编辑的源qq
    changeCurrentSource(source: Source) {
      this.editing = true
      this.editingJsSource = isJsBookSource(source)
      this.editHistory(this.currentSource) // 记录被替换的状态供撤销
      this.currentSource = JSON.parse(JSON.stringify(source))
    },
    /** 新建声明式书源/订阅源：空表单进入编辑 */
    createSource() {
      this.changeCurrentSource(JSON.parse(JSON.stringify(emptySource)))
    },
    /** 新建 JS 源：载入模板进入脚本编辑；模板拉取失败抛错由调用方提示 */
    async createJsSource() {
      const template = await fetchJsSourceTemplate()
      const source = JSON.parse(JSON.stringify(emptyBookSource)) as BookSoure
      // 显式带上基础键，保证列表/调试等按 key 取值的路径正常
      source.bookSourceUrl = ''
      source.bookSourceName = ''
      source.bookSourceType = 0
      source.mainJs = template
      this.changeCurrentSource(source)
    },
    // update editTab tabName and editTab info
    changeTabName(tabName: string) {
      this.currentTab = tabName
      localStorage.setItem('tabName', tabName)
    },
    changeEditTabSource(source: Source) {
      this.editTabSource = JSON.parse(JSON.stringify(source))
    },
    editHistory(history: Source) {
      let historyObj
      // 深克隆快照：调用方之后还会原地改 currentSource（如脚本编辑），不能存引用
      const snapshot = JSON.parse(JSON.stringify(history))
      if (localStorage.getItem('history')) {
        historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.new.push(snapshot)
        if (historyObj.new.length > 50) {
          historyObj.new.shift()
        }
        if (historyObj.old.length > 50) {
          historyObj.old.shift()
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      } else {
        const arr = { new: [snapshot], old: [] }
        localStorage.setItem('history', JSON.stringify(arr))
      }
    },
    editHistoryUndo() {
      if (localStorage.getItem('history')) {
        const historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.old.push(JSON.parse(JSON.stringify(this.currentSource)))
        if (historyObj.new.length) {
          this.currentSource = historyObj.new.pop()
          this.editing = true
          this.editingJsSource = isJsBookSource(this.currentSource)
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      }
    },
    editHistoryRedo() {
      if (localStorage.getItem('history')) {
        const historyObj = JSON.parse(localStorage.getItem('history')!)
        historyObj.new.push(JSON.parse(JSON.stringify(this.currentSource)))
        if (historyObj.old.length) {
          this.currentSource = historyObj.old.pop()
          this.editing = true
          this.editingJsSource = isJsBookSource(this.currentSource)
        }
        localStorage.setItem('history', JSON.stringify(historyObj))
      }
    },
    clearAllHistory() {
      localStorage.setItem('history', JSON.stringify({ new: [], old: [] }))
    },
    clearEdit() {
      this.editTabSource = {} as Source
      this.currentSource = JSON.parse(JSON.stringify(emptySource)) //复制一份新对象
      this.editing = false // 返回新建入口
      this.editingJsSource = false
    },

    // clear all source
    clearAllSource() {
      this.bookSources = []
      this.rssSources = []
      this.savedSources = []
    },
  },
})
