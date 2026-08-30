import type { BookSoure, RssSource, Source } from '../source'
import { isNullOrBlank } from './utils'

const isBookSource = (source: Source): source is BookSoure =>
  'bookSourceName' in source

/** mainJs 非空白即为 JS 源（与 App 端 BookSource.isJsSource() 一致；mainJs 是书源独有字段，天然区分订阅源） */
export const isJsBookSource = (source: Source): source is BookSoure =>
  'mainJs' in source && !isNullOrBlank(source.mainJs)

/** 拉取 JS 源模板（构建期从 App assets 同步进 web 静态目录，与 App 端新建入口同源） */
export const fetchJsSourceTemplate = async (): Promise<string> => {
  const resp = await fetch('js_source_template.js')
  if (!resp.ok) throw new Error(`模板加载失败: HTTP ${resp.status}`)
  const text = await resp.text()
  if (!text.trim()) throw new Error('模板加载失败: 内容为空')
  return text
}

export const isInvaildSource: (source: Source) => boolean = source => {
  if (isBookSource(source)) {
    return (
      !isNullOrBlank(source.bookSourceName) &&
      !isNullOrBlank(source.bookSourceUrl) &&
      !isNullOrBlank(source.bookSourceType)
    )
  }
  return !isNullOrBlank(source.sourceName) && !isNullOrBlank(source.sourceUrl)
}

export const getSourceUniqueKey = (source: Source) =>
  isBookSource(source) ? source.bookSourceUrl : source.sourceUrl
export const getSourceName = (source: Source) =>
  isBookSource(source) ? source.bookSourceName : source.sourceName

export const isSourceMatches: (source: Source, searchKey: string) => boolean = (
  source,
  searchKey,
) => {
  // TODO: 正则和普通字符串识别 识别 * . \ [ ] <= <! != = ?: () \d\w\s\...
  if (isBookSource(source)) {
    return (
      (source.bookSourceName.includes(searchKey) ||
        source.bookSourceUrl.includes(searchKey) ||
        source.bookSourceGroup?.includes(searchKey) ||
        source.bookSourceComment?.includes(searchKey)) ??
      false
    )
  }
  return (
    (source.sourceName.includes(searchKey) ||
      source.sourceUrl.includes(searchKey) ||
      source.sourceGroup?.includes(searchKey) ||
      source.sourceComment?.includes(searchKey)) ??
    false
  )
}

export const convertSourcesToMap = (sources: Source[]): Map<string, Source> => {
  const map = new Map()
  sources.forEach(source => map.set(getSourceUniqueKey(source), source))
  return map
}

export const normalizeSource = (source: any) => {
  for (const key in source) {
    const value = source[key]
    if (
      value === '' ||
      value === null ||
      (typeof value === 'string' && !value.trim())
    ) {
      delete source[key]
    } else if (value instanceof Object) {
      normalizeSource(value)
    }
  }
}

export const emptyBookSource = {
  ruleSearch: {},
  ruleBookInfo: {},
  ruleToc: {},
  ruleContent: {},
  // ruleReview: {},
  ruleExplore: {},
} as BookSoure
export const emptyRssSource = {} as RssSource
