# DSH 本地补丁：模型候选默认「全不选」

## 目标
DeepSeek Harness 的「模型」设置页里，添加/编辑模型供应商后点「获取可用模型」，
弹窗里的候选模型默认全部勾选。模型一多就很难操作。本补丁改为 **默认全不选**。

## 根因定位（一次排查结论，以后直接复用）
- 包：`@deepseek-ai/dsh-client-ui-settings-models`
- 文件：`lib/client.js`（npm 编译产物；源码在 `deepseek-harness` 仓库
  `packages/client/ui-settings-models/src/client/ModelListEditor.tsx`）
- 组件：`ModelListEditor` 内的 `fetchModels()`，候选弹窗默认勾选状态由
  `setPicked(...)` 初始化。

## 精确改动（仅一行）
```js
// 旧：默认勾选所有「新发现」的模型
setPicked(new Set(found.filter((model) => !known.has(model.id)).map((model) => model.id)));

// 新：默认什么都不选
setPicked(new Set());
```

## 文件实际位置（路径含一次性哈希，每次 npx 拉包会变）
```
%LOCALAPPDATA%\npm-cache\_npx\<hash>\node_modules\@deepseek-ai\dsh-client-ui-settings-models\lib\client.js
```

## 重新应用的方法（二选一）
1. 直接跑幂等脚本：
   ```powershell
   pwsh -File dsh-patch-model-select-none.ps1
   # 或指定文件
   pwsh -File dsh-patch-model-select-none.ps1 -Path "C:\...\client.js"
   ```
2. 手动改：把上面那一行旧串替换成 `setPicked(new Set());`。

## 重要注意
- **沙箱提权**：如果由沙箱内的 coding agent 来改，该路径在会话工作区之外，
  需要用 `danger-full-access` 提权（会弹用户批准）。
- **不持久**：`npx @deepseek-ai/dsh` 重新拉包、或清 npx 缓存会被覆盖，需重跑脚本。
- **生效方式**：取决于 GUI 是否直接 serve 这份 `node_modules`；若用预构建静态产物，
  需要 rebuild + 刷新页面。
- **残留死代码**：第 809 行 `const known = new Set(...)` 改成后不再被使用，无害，可忽略。
- **验证**：改完后在模型页点「获取可用模型」，候选默认应全部不勾选。

## 可选扩展（当前未启用）
如需「全选 / 清空」按钮，还需：
1. 在候选弹窗 `footer` 的「添加所选」按钮前，加两个 `Button`（variant outline），
   分别 `setPicked(new Set((candidates ?? []).map(m => m.id)))` 和 `setPicked(new Set())`；
2. 给 en/zh locale 各加 `fetchSelectAll` / `fetchSelectNone` 两个 key。
当前需求只做「默认全不选」，故未启用。
