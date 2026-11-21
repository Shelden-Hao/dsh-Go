# ReadCompat 调试清单

## 当前状态（2026-11-21）

| 项目 | 状态 |
|---|---|
| 基线 | `origin/main` = `da798027`（v1.3.0），已 `git fetch` |
| 工作分支 | `feat/a11y-visual-only`，在最新 main 上 cherry-pick `760a5053`（原 ReadCompat）后再加 visualOnly 修复 |
| Flutter / SDK | `C:\Develop\flutter` 3.47.9、`C:\Develop\android-sdk`、JDK `C:\Develop\jdk`、Gradle 9.3.1 |
| 真机 | 已连接，`io.github.ccch1mneyyy.dsh_app.test`（side build，调试签名） |
| 实验开关 | `ReadCompat.relaxed` 默认开启，可用 `readcompat --es set on|off` 运行时切换 |

## 实测结论

1. **微信聊天页**：能读。节点树是空壳（`rootInActiveWindow children=0 visible=false`），但 ReadCompat 放开了 `emptyFront/unreadable` 与 OCR 门控，截图 + 本地 OCR 能拿到聊天文字。
2. **微信内京东小程序详情页**：原先读不到，报「未知应用 · 读不到前台界面」。真机 `windows` 探针确认：
   ```
   rootInActiveWindow: window=null pkg=null children=null
   id=... type=3 ... root=com.vivo.upslide children=1
   id=... type=3 ... root=com.android.systemui children=2
   ```
   连一个 `TYPE_APPLICATION` 窗口都没有。于是 `chosen` 为空 → `cap.windows.isEmpty()` → `poorTree=false` → **截图和 OCR 根本没被调用**（即使显式 `image=true` 也只是拿到 JPEG，没有文字）。
3. 关键区分：小程序页 `secure=false`，全屏截图 129KB 正常产出，所以不是 FLAG_SECURE 红线，可以走 OCR。
4. **最终验证通过**（2026-11-21 16:26，京东小程序商品页）：`poor=true`、`image=122721B`，输出 61 条本机文字区域，包括「该商品已下架」「戴尔(DELL)笔记本电脑 游匣G15 15.6英寸」「游戏本(八核7 16G512G RTX3060显卡…」「¥6999.00」「相似商品」等。之前微信聊天页仍可读。

途中补的三道门（都在 `MainScreenDriver.kt`）：
- `ocrAllowed()` 的 `denyPackages` 检查需要非空 packageName，visualOnly 没有 → 实验开关下跳过；
- `verifyOcrContext()` 的 `reusableCoordinates()` 要求 `!unavailable` 且 packageName 非空，visualOnly 都不满足 → 实验开关下跳过这条上下文复用校验（前后两次 `ocrAllowed` 和截止时间照常检查）；
- 这两处是实验性放宽，副作用见代码注释。

## 本次新增修复（visualOnly）

- `TreeCapture.Capture` 增加 `visualOnly` 字段：仅当 `ReadCompat.relaxed && chosen.isEmpty() && hidden == null && unreadable == null && !ownFront && !systemFront` 时置真。FLAG_SECURE（`hiddenWindowTitle`）分支原样保留。
- `MainScreenDriver.observe()` 用局部 `poor = snap.screen.poorTree || cap.visualOnly` 驱动截图与本地 OCR；无窗口时强制全屏截图，不按窗口 id 截。
- `ocrAllowed()` 在 visualOnly 时放宽「per-app 视觉 scope」与「单窗口 + visualComplete」两项，其余安全条件（非安全窗、非敏感页、非 DSH 自己、无密码框、denyPackages 等）全部保留。
- `looksUnfinished()` 对 visualOnly 短路，不再空转重试四次。
- 提示文案改为「这一屏没有可读元素，只能尝试截图识别文字；按元素点击不可用」。

副作用：小程序页仍**无法按元素 id 点击**（没有节点树），只能 `tap_xy` 或 handoff；这是只读兼容，不是完整无障碍读屏。

## 安装（已自动做，记录备查）

```bash
bash /c/Codefield/_toolchain/install_new.sh
```
脚本流程：注入原版 runtime → zipalign → debug keystore 签名 → `adb install -r`。

## 验证（真机复制粘贴）

```bash
ADB=/c/Develop/android-sdk/platform-tools/adb.exe

# 1) 手机上进微信 → 文件传输助手 → 点开京东小程序商品页

# 2) 看窗口枚举，确认小程序窗口对无障碍不可见
$ADB shell am broadcast -a io.github.ccch1mneyyy.dsh_app.A11Y_PROBE --es cmd windows --ei seq 1
$ADB shell run-as io.github.ccch1mneyyy.dsh_app.test cat files/a11y-probe/last.txt

# 3) 读屏（不传 image，走自动截图 + OCR）
$ADB shell am broadcast -a io.github.ccch1mneyyy.dsh_app.A11Y_PROBE --es cmd screen --ez detail true --ei seq 2
$ADB shell run-as io.github.ccch1mneyyy.dsh_app.test cat files/a11y-probe/last.txt
```

判读：

| 输出 | 说明 |
|---|---|
| `poor=true` 且正文出现页面文字 | 修复生效 |
| `secure=true` | 系统安全窗，红线不动，无解 |
| `poor=false`、`image=...B` 但没文字 | OCR 没跑或被拒，看 `localText` 原因 |
| `note` 仍是「读不到前台界面」 | visualOnly 没置上，检查 `hiddenWindowTitle` 是否被设成非 null |

## A-B 对照（可选）

```bash
$ADB shell am broadcast -a io.github.ccch1mneyyy.dsh_app.A11Y_PROBE --es cmd readcompat --es set off --ei seq 3
$ADB shell am broadcast -a io.github.ccch1mneyyy.dsh_app.A11Y_PROBE --es cmd screen --ez detail true --ei seq 4
$ADB shell run-as io.github.ccch1mneyyy.dsh_app.test cat files/a11y-probe/last.txt

$ADB shell am broadcast -a io.github.ccch1mneyyy.dsh_app.A11Y_PROBE --es cmd readcompat --es set on --ei seq 5
$ADB shell am broadcast -a io.github.ccch1mneyyy.dsh_app.A11Y_PROBE --es cmd screen --ez detail true --ei seq 6
$ADB shell run-as io.github.ccch1mneyyy.dsh_app.test cat files/a11y-probe/last.txt
```

## 回退

```bash
git -C C:/Codefield/dsh-Go checkout origin/main
```
