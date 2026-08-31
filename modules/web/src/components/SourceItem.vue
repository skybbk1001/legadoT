<template>
  <el-checkbox
    size="large"
    border
    :value="sourceUrl"
    :class="{
      error: isSaveError,
      edit: sourceUrl == currentSourceUrl,
    }"
  >
    <span>{{ getSourceName(source) }}</span>
    <span class="item-right">
      <el-tag v-if="isJs" size="small" type="warning" disable-transitions
        >JS</el-tag
      >
      <el-button text :icon="Edit" @click="handleSourceClick(source)" />
    </span>
  </el-checkbox>
</template>

<script setup lang="ts">
import { Edit } from '@element-plus/icons-vue'
import { getSourceUniqueKey, getSourceName, isJsBookSource } from '@/utils/souce'
import type { Source } from '@/source'

const props = defineProps<{
  source: Source
}>()

const store = useSourceStore()

const isJs = computed(() => isJsBookSource(props.source))
const currentSourceUrl = computed(() => store.currentSourceUrl)
const sourceUrl = computed(() => getSourceUniqueKey(props.source))

const handleSourceClick = (source: Source) => {
  store.changeCurrentSource(source)
}
const isSaveError = computed(() => {
  const map = store.savedSourcesMap
  if (map.size == 0) return false
  return !map.has(sourceUrl.value)
})
</script>
<style lang="scss" scoped>
:deep(.el-checkbox__label) {
  flex: 1;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.item-right {
  display: flex;
  align-items: center;
  gap: 4px;
}
.error {
  border-color: var(--el-color-error) !important;
  color: var(--el-color-error) !important;
  --el-checkbox-checked-text-color: var(--el-color-error);
  --el-checkbox-checked-bg-color: var(--el-color-error);
  --el-checkbox-checked-input-border-color: var(--el-color-error);
}
.edit {
  border-color: var(--el-color-dark) !important;
}
</style>
