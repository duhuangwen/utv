# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

uTV — Android / Android TV 直播播放器，支持 IPTV 组播（源自北京联通版）与 M3U 订阅源（单模块 `:app`，Java 包名 `com.bjunicom.iptv`）。纯 Java，源码兼容级别 **Java 8**（`sourceCompatibility 1_8`），AGP 8.1.4 / Gradle 8.5（腾讯镜像），compileSdk/targetSdk 34，minSdk 21。播放内核为 `ijkplayer 0.8.8`，`src/main/jniLibs` 下是**自编译、开启 MP2 解码器**的四架构 `libijk*.so`，不可用 Maven 产物替换。

## Commands

```powershell
./gradlew assembleDebug            # debug 包（同样使用 release keystore 签名）
./gradlew assembleRelease          # 签名口令可由 KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD 覆盖
./gradlew testDebugUnitTest        # Robolectric 单测（@Config(sdk = 33)）
./gradlew testDebugUnitTest --tests com.bjunicom.iptv.M3uParserTest
./gradlew testDebugUnitTest --tests "com.bjunicom.iptv.StartupSmokeTest.testMainActivityStartupAndSurfaceCreated"
```

- 需要 `local.properties`（`sdk.dir`）或 `ANDROID_HOME`；lint 配置为 `abortOnError false`，不作为门禁。
- `StartupSmokeTest` 会真实启动 `IptvApplication` + `MainActivity`（加载 ijk native 库失败时仅告警），改动启动链路/布局 id 后必须跑。

## Architecture

### 播放链路（核心，跨多个文件）

1. **URL 解析**：频道原始地址是 `rtp://239.3.x.x:port` 组播。`network/StreamUrlResolver` 按 `IptvSettings.getStreamMode()` 转换：
   - `STREAM_MODE_UDPXY`（默认）→ `http://<udpxy>/rtp/<ip:port>`
   - `STREAM_MODE_LOCAL_PROXY` / `STREAM_MODE_DIRECT_RTP` → 都走 `http://127.0.0.1:<port>/rtp/...`（`LocalMulticastProxyServer`）。原因：ijk 0.8.8 的 ffmpeg 未编译 `rtp://` 协议，且原生 `udp://` 无法绑定无默认网关的 IPTV 专网网卡、无法剥离 RTP 头。
2. **`IptvApplication`** 启动时加载 ijk native 库、持有 Wi-Fi `MulticastLock`、启动 `LocalMulticastProxyServer`（IGMP 组播 → HTTP-TS），并把未捕获异常写入 `filesDir/last_crash.log`。
3. **`player/IjkVideoView.openVideo()`** 有两条路径：
   - **分离池化管线**（`PLAYER_IJK` 且 scheme 为 http/https/rtp/udp）：从 `StreamDownloaderPool` 取 `PooledStreamDownloader`（2MB 环形缓冲，本身实现 `IMediaDataSource`，也能直接加入 IGMP 组播）→ 解析 PAT/PMT 得到 `TsCodecDescriptor` 并做 IDR 关键帧对齐（避免切台初期 0.5x 慢放）→ 回调 `onCodecParsed` 后按编码规格从 `CodecDecoderPool` 取 `IjkMediaPlayer` 并 `setDataSource(downloader)`。
   - **常规路径**（ExoPlayer / AndroidMediaPlayer）：`setDataSource(uri)`，rtp/udp 先经本地代理转换。
   - 回调全部用 `mPlaybackSessionToken` 校验，切台后旧会话的异步事件必须丢弃——新增异步回调时沿用此模式。
4. **AC-3 / E-AC-3 伴音**：ijk 的 ffmpeg 不解码杜比音轨，`TsCodecDescriptor.requiresHardwareAudioCompanion()` 为真时由 `TsHardwareAudioCompanion` 通过下载器的 `TsPacketTeeListener` 旁路拿 TS 包（不重复建连），用 `MediaCodec` + `AudioTrack`（含 HDMI passthrough 回退）输出；硬件解码器同样由 `CodecDecoderPool` 池化。

### 频道数据

- **直播源**由 `IptvSettings.getSourceUrl()` 决定：http(s) 订阅（默认 iptv-org `cn.m3u`，拉取后缓存为 `filesDir/source_cache.m3u` + `.url` 元数据；从未拉取成功时回落到随包快照 `assets/iptv-org-cn.m3u`）、`asset://<文件>` 内置列表（如安徽联通 `ah-unicom-iptv.m3u`）或 `local://imported` 本地导入。`ChannelRepository.refreshSourceAsync()` 在启动时与设置页触发；`switchSource()`/频道表内容变化时必须删除 `modified_iptv.m3u`，否则 `mergeScanResults` 会把旧源频道当作「动态扫描发现」回灌。
- `parser/PlaylistNormalizer` 把 iptv-org 风格条目（英文名、`(720p)`、`[Not 24/7]`）规整为中文分组、台号（CCTV-N→N，CCTV-5+→18，其余从 21 起按央视/卫视/其它分配）与清晰度并按台号排序；标题已含 `[台号]` 的列表视为已规整不处理。分类列表由 `getCategoryGroups()` 按频道实际分组动态生成。非组播频道以 `rawUrl` 作为 `getMulticastKey()`。
- 单播源不走池化管线：`IjkVideoView.isMulticastSource()` 为假时走常规路径，因为 `PooledStreamDownloader` 只认裸 TS，无法处理 m3u8。内置 `libijkffmpeg.so` **未编译 TLS**（仅有 HLS demuxer），所以 `https` 源自动改用 `IjkExoMediaPlayer`；http 源用 ijk 失败时 `MainActivity` 的 onError 会 `setForceExoPlayer(true)` 重试一次。组播扫描/udpxy/本地代理只对 `rtp://` 源有意义；巡检对单播源并发做 HTTP/HLS 探测（`probeUnicastHttpStream`）。
- `applicationId` 已改为 `com.ahunicom.iptv`，Java 包名仍为 `com.bjunicom.iptv`。
- `parser/M3uParser` 负责解析/导出，识别 `tvg-id`/`tvg-name`/`tvg-logo`/`group-title` 以及 `check_iptv_m3u.sh` 风格的轨道/分辨率信息；修改格式需同步 `M3uParserTest`。
- 所有用户设置与收藏都在 `config/IptvSettings`（SharedPreferences）。

### 扫描 / 巡检

`scanner/MulticastScannerEngine`（UI 为 `ui/ScannerActivity`）三种模式：巡检现有列表、组播网段扫描、通过 `ProcessBuilder` 释放并执行 `assets/check_iptv_m3u.sh`。产物为 `iptv_YYYYMMDD_HHMMSS/` 下的 `results.csv`（UTF-8 BOM）与 `modified_iptv.m3u`。Java 侧逻辑是在复刻该脚本的输出格式，改任何一边都要对齐另一边。

- 根目录 `check_iptv_m3u.sh` 与 `app/src/main/assets/check_iptv_m3u.sh` 内容相同，修改需同步两份。
- 节目源与脚本源自上游 `wuwentao/bj-unicom-iptv`。

### UI

`ui/MainActivity`（~1400 行）同时承担 OSD、频道抽屉、数字选台、遥控器按键（方向/数字/OK/MENU/`PROG_RED|GREEN|YELLOW|BLUE`）与触屏手势；必须同时兼顾 D-pad 焦点导航与触屏。键位映射见 `ReadMe.md` 交互表。

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **utv** (1184 symbols, 3062 relationships, 100 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/utv/context` | Codebase overview, check index freshness |
| `gitnexus://repo/utv/clusters` | All functional areas |
| `gitnexus://repo/utv/processes` | All execution flows |
| `gitnexus://repo/utv/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
