# PCL 公开功能、实现位置与 Cairn 对照

调查对象：[Meloong-Git/PCL](https://github.com/Meloong-Git/PCL)，提交 `6b9a50df911c9d68b69b24c979efba477fb14b67`（2026-10-20，`2.99.0.0 正式版`）。

本表按公开源码的模块、配置项和入口归纳。`PCL-source-index.json` 另列每个 VB/C#/XAML 文件中的可识别声明名、行号、设置键和功能标签；不包含实现代码正文。已检出并纳入公开的 MeloongCore 子模块 `98157d65c7627721c816c02e8be96ac4fe61d615`，其 Shared 提供配置、日志、任务、进度、路径与文件等通用能力，Wpf 提供窗口工具和转换器；这些通用能力在 Cairn 中使用 Java 标准 API 独立实现。PCL 的 README 明示只包含大部分源代码，部分安全、更新、远程配置及服务材料未公开，不能据此承诺已取得商业发行版的全部实现。

Cairn 以公开的 Minecraft/上游协议独立编写 Java 实现，不是 PCL 的语言翻译或换皮。字符界面按照用户要求重新设计。`已实现` 表示存在可执行代码路径；具体实测范围见《验证报告.md》，不能把代码存在等同于所有外部环境均已验证。

## 游戏与账户

| PCL 功能 | 公开实现线索 | Cairn 指令与实现 | 当前边界 |
| --- | --- | --- | --- |
| 启动任务链、检查、补全、Java、参数、进程 | `ModLaunch.vb`、`ModWatcher.vb` | `play`；`Launch`、`GameWorker` | 已实现，真实进程使用隔离夹具验证 |
| 离线账户、离线 UUID | `ModLaunch` 登录模块、`PageLoginLegacy` | `account offline`；`Accounts.offline` | 已实现 |
| 微软账户登录 | `ModLaunch`、`PageLoginMs`、`ModSecret` | `account microsoft`；OAuth 设备代码 → Xbox → XSTS → Minecraft | 需要自有合资格 Client ID，未实测个人授权 |
| 账户缓存和刷新 | 同上 | AES-GCM 凭据库，`account refresh/use/remove` | 已实现；不会导入 PCL 私有密文 |
| 第三方 Yggdrasil | `PageLoginAuth`、`ModLaunch` | `account auth`，认证/刷新，authlib-injector | 需自备注入器及有效服务 |
| 统一通行证 | `PageLoginNide`、`ModLaunch` | `account nide` | 使用该服务的 Yggdrasil 地址适配，未在线验证 |
| 角色选择 | 登录响应 selected/available profiles | 第三方登录可指定角色 ID/名称 | 已实现 |
| 正版皮肤下载/换肤/重置 | `ModMinecraft` 皮肤区、登录皮肤页 | `skin download/set-url/reset` | 下载使用会话材质协议；修改需微软账户 |
| 离线皮肤 | `PageSetupLaunch`、`ModLaunch` | `skin offline` 本地资源包 | 仅本机可见；不是所有历史版本精确等价 |
| 购买提示 | `McLaunchPrecheck` | 离线提示、about、购买链接 | 独立提示，不复刻 PCL 赞助策略 |
| 游戏服务器直连 | 版本独立设置、启动参数 | `play --server`；现代 quick play 或传统 server/port | 已实现；SRV 自动解析未实现 |
| 快速进入单机世界 | 启动参数与自定义事件 | 实例 `world`，现代 quickPlaySingleplayer | 仅对应游戏支持该参数时生效 |

## 版本、实例与启动设置

| PCL 功能 | 公开实现线索 | Cairn 指令与实现 | 当前边界 |
| --- | --- | --- | --- |
| 多游戏根目录 | `ModMinecraft` 文件夹区 | `root add/list/versions/adopt` | 外部目录读取后复制导入，不原位编辑 |
| 本地版本识别与分类 | `McInstance`、版本列表页面 | `versions installed`、`instance list` | 显式类型/加载器字段；不复刻全部启发式分类 |
| 版本选择 | `PageSelect`、`PageLaunch` | `instance use`、默认实例 | 已实现 |
| 收藏、隐藏、描述 | `PageInstanceOverall` | `instance star/hide/show/describe` | 已实现 |
| 重命名、复制、移除 | 版本管理页面 | `instance rename/clone/remove` | 以独立实例 ID 操作，共享版本保留 |
| 数据隔离 | `LaunchArgumentIndie*`、`VersionArgumentIndie*` | 默认每实例隔离；全局/独立 `isolation` | 不自动套用 PCL 的五种分类策略 |
| 打开版本/Mod/存档/截图目录 | `PageInstanceOverall` | `instance paths` 输出路径 | 按无图形界面要求不打开资源管理器 |
| 启动脚本导出 | 同上 | `launch-script` | 只保存启动器调用，不保存令牌 |
| 最大/最小内存 | `ModLaunch`、启动设置 | `memoryMiB/minMemoryMiB` | 已实现；没有自动内存整理 |
| Java 自动选择 | `ModJava`、`PCLCS/Java.cs` | 扫描、版本匹配、官方补齐 | 依据 manifest 与发行时间的默认要求 |
| Java 添加、单独设置 | 同上 | `java add/use`、`instance set ... java` | 已实现 |
| 分辨率、全屏 | 启动设置、启动参数 | `width/height/fullscreen` | 标准游戏参数；不操控系统窗口最大化 |
| GC 策略 | `LaunchAdvanceGC` | G1、ZGC、兼容的分代 ZGC、none | 独立标准参数，没有复制 PCL 调优常量 |
| JVM/游戏附加参数 | 启动设置 | `jvmArgs/gameArgs` JSON argv | 已实现 |
| 启动前命令、等待 | `LaunchAdvanceRun` 等 | `before` argv，等待并限制超时 | 已实现；另有 `after` |
| 游戏自定义信息 | `LaunchArgumentInfo` | `customInfo` / version_type | 新游戏若移除此参数则不生效 |
| 游戏标题、窗口可见性、优先级 | `ModLaunch`、`ModWatcher` | 命令行可保留/退出，游戏后台监护 | 原生窗口标题/优先级未实现 |
| 中文路径/旧 Java 特例 | Java Launch Wrapper 等资源 | Java ProcessBuilder 与 UTF-8 argv | 未包含 PCL 全部包装器和兼容补丁 |
| LWJGL Unsafe Agent | `PCLCS/Launch` | 未捆绑 | 未实现专用注入 |
| Windows 高性能 GPU 注册表 | 启动设置 | 未修改系统注册表 | 未实现 |

## 下载、修复与加载器

| PCL 功能 | 公开实现线索 | Cairn 指令与实现 | 当前边界 |
| --- | --- | --- | --- |
| 官方版本清单、正式/快照/古老版本 | `ModDownload`、`PageDownloadInstall` | `versions list`，筛选、分页、刷新 | 已实现，清单来自 Mojang |
| 最新版本通知/更新日志入口 | `McDownloadClientUpdateHint`、下载菜单 | `news`、官方文章地址、版本详情 | 不后台轮询 PCL 公告 |
| 原版客户端 | `McDownloadClientLoader` | `install` | Mojang 清单地址+哈希 |
| 官方服务端保存 | `McDownloadMenuSaveServer` | `server download` | 已实现，不代替用户接受 EULA |
| 库/本地库/日志配置 | `ModMinecraft` 库区、`ModDownload` | `Minecraft.libraries/repair/natives` | 操作系统规则、Maven 坐标、SHA-1、本地库提取 |
| Assets、旧资源目录 | `ModMinecraft` 资源区 | 资源对象、索引、virtual/map_to_resources | 已实现 |
| 补全、损坏修复 | `DlClientFix` | `repair`、默认启动前补全 | 已实现 |
| 并发、重试、任务取消 | `ModNet`、`ModLoader` | `Jobs`、`Net`、`task` | 最多 32 下载线程 |
| 断点续传、校验和 | `ModNet` | Range、Content-Range、校验后提交 | 夹具覆盖续传和错误断点 |
| 下载速度限制 | `ToolDownloadSpeed` | `speedKiB` | 有上限时串行，保证总上限 |
| 镜像/来源顺序 | `DlSource*` | `source add/edit/check/use/remove` | 默认仅官方，可自行定义清单、客户端、库、Assets 和 Java 源；无自动源竞速 |
| Fabric | `ModDownloadLib` Fabric 区 | `loader install fabric` | 上游 profile；支持指定版本及默认稳定版 |
| Fabric API / OptiFabric | 自动安装组合页 | `content install fabric-api/optifabric` | 用户显式选择配套资源 |
| Quilt | 当前下载实现与资源元数据 | `loader install quilt` | 上游 profile |
| Forge | `ModDownloadLib` Forgelike 区 | `loader install forge` | 官方安装器无界面，现代版本已实测 |
| NeoForge | 同上 | `loader install neoforge` | 官方安装器；包括 1.20.1 旧坐标分支 |
| OptiFine 下载与安装 | OptiFine 下载区 | 官网列表、下载入口、反射调用公开 doInstall；本地安装 | 上游网页变化可能影响在线下载 |
| LiteLoader | LiteLoader 下载区 | 上游版本索引与 LaunchWrapper 描述 | 上游旧版本兼容需逐个验证 |
| 自动组合、升级替换加载器 | 安装/修改页面 | 新建加载器实例、已有实例复制与资源安装 | 不包含 PCL 所有组合兼容判定 |
| Java 自动下载 | `ModJava` | `java install`、启动时自动补齐 | 默认 Mojang，可配置兼容的 Java 索引/描述/文件源 |

## 资源、Mod 与整合包

| PCL 功能 | 公开实现线索 | Cairn 指令与实现 | 当前边界 |
| --- | --- | --- | --- |
| Mod 搜索、详情、版本 | `ResourceSearcher/Project/Version` | `content search/info/versions` | Modrinth、CurseForge |
| 光影、资源包、数据包 | 资源下载页面 | `--type shader/resourcepack/datapack` | 数据包要求指定已有世界 |
| 项目类型/游戏/加载器过滤 | 搜索请求对象 | 对应 `--type/--game/--loader` | 已实现 |
| 必须依赖下载 | `ResourceVersion`、资源详情页 | 递归 required 依赖、循环限制 | 不自动安装 optional 依赖 |
| 平台文件分发限制 | CurseForge 下载解析 | 需要密钥，禁止分发时要求人工下载 | 不绕过作者限制 |
| 中文搜索翻译、Wiki 映射 | 中文搜索区、`WikiEntries.txt` | 平台本身 query 搜索 | 未复制中文别名/翻译数据库 |
| 本地 Mod 信息 | `LocalResourceLoaders/File` | ZIP 元数据提取 | Fabric/Quilt/TOML/mcmod.info |
| Mod 启用/禁用/删除 | `PageInstanceMod` | `mod enable/disable`、`content remove` | 删除可恢复 |
| Mod 更新 | 本地资源网络信息 | 锁定文件清单、检查/应用更新 | 仅追踪通过资源安装器管理的文件 |
| 重复/兼容检查 | 本地 Mod 管理与崩溃分析 | 重复 Mod ID、基础版本/加载器筛选 | 未实现全量语义依赖求解 |
| Modrinth 整合包 | `InstallPackModrinth` | `pack import/fetch` | v1 index、哈希、client env、overrides |
| CurseForge 整合包 | `InstallPackCurseForge` | `pack import/fetch --source curseforge` | manifest、加载器、必需文件与 overrides |
| MultiMC/Prism | `InstallPackMMC` | mmc-pack components 与 .minecraft | 主要标准组件，历史补丁变体未穷举 |
| MCBBS | `InstallPackMCBBS` | packmeta/addons/overrides | 常见结构适配，未验证全部历史包 |
| HMCL | `InstallPackHMCL` | modpack.json 与版本/overrides | 常见结构适配，未验证全部历史包 |
| 普通目录包/PCL包 | `InstallPackLauncherPack/Compress` | 标准版本目录描述、库和游戏内容 | 不导入 PCL 私有设置/账户；多叶版本要求拆分 |
| 整合包导出 | `PageInstanceExport` | `pack export` | Cairn 格式，不输出所有生态原生格式 |
| ZIP 路径与导入失败恢复 | 解压与安装任务 | 路径检查、展开限额、临时实例、回收 | 不执行包内钩子 |

## 日志、诊断、联机与界面

| PCL 功能 | 公开实现线索 | Cairn 指令与实现 | 当前边界 |
| --- | --- | --- | --- |
| 游戏进程监视 | `ModWatcher` | 独立 `GameWorker`、PID+开始时间、退出状态 | 已实现 |
| 实时日志、停止游戏 | `ModWatcher`、任务页 | `logs --follow`、`stop` | UTF-8 增量、截断/轮转处理 |
| 崩溃日志整理、导出 | `ModCrash` | `crash`、`report` | 脱敏日志和基础环境 |
| 崩溃原因与建议 | `CrashAnalyzer` 的原因枚举及规则 | 20 类独立规则与证据 | 未复刻 PCL 全部规则/Mod 特判 |
| 局域网/远程房间 | `PageLinkMain`、`PageSetupLink` | LAN 广播、EasyTier CLI 适配 | 自备节点/工具；不等于 PCL 房间服务 |
| 节点、NAT、延迟状态 | 同上 | 本地进程与日志、服务器状态协议 | 未实现完整 NAT 诊断与 PCL 邀请码协议 |
| 图形主页、XAML 自定义事件 | `ModEvent`、Custom.xaml、UI 设置 | 字符工作区、别名、脚本、变量 | 按要求独立替代，不加载 XAML |
| 主题、透明度、背景、Logo | `ModSecret`、`PageSetupUI` | ASCII/Unicode、颜色开关、自定义 banner | 原图形效果按要求不实现 |
| 背景音乐 | `ModMusic` | `music play/stop`，音量与循环 | WAV/AIFF/AU；无 MP3/OGG 解码 |
| 帮助、关于、反馈、功能入口 | `PageOther*` | `help`、about、官方链接与功能状态 | 没有内置网页浏览器 |
| 自定义命令动作 | `ModEvent` | 自有指令脚本与 before/after argv | 不解释 PCL XAML 事件 |
| 设置导入导出、缓存清理 | `PageSetupSystem` | `config import/export`、`cache clean` | 私密 API Key 不导出，清理可恢复 |
| 今日人品、百宝箱等彩蛋 | `ModEvent`、`PageOtherTest` | 未实现 | 不影响游戏下载和运行 |
| 自动更新、公告、匿名遥测、赞助解锁 | `ModSecret`、远程配置 | 本地 about/news/手动替换 JAR | PCL 专属服务未实现，无遥测 |

## 实现结构

| Java 类 | 责任 |
| --- | --- |
| `Main`、`Shell`、`Help`、`Ui`、`Terminal` | 命令参数、字符工作区、脚本、别名、帮助、控制台编码和输出 |
| `Store`、`Util`、`Json` | 配置、凭据、锁、路径、ZIP、原子写入和 JSON |
| `Net`、`Jobs` | HTTPS、来源限制、续传、哈希、并发、取消和限速 |
| `Sources` | 自定义下载源、分类 URL 映射、当前源校验与缓存指纹 |
| `Minecraft` | 清单、继承、库、Assets、Natives 和服务端 |
| `JavaRuntime` | 扫描、探测、匹配和 Mojang 运行时安装 |
| `Accounts` | 离线、微软、Yggdrasil 和皮肤协议 |
| `Loaders` | 上游加载器列表、描述及无界面安装 |
| `Content`、`Packs`、`Instances` | 资源、Mod、整合包与实例生命周期 |
| `Launch`、`GameWorker`、`LogCursor` | 参数构建、独立监护、日志增量和会话管理 |
| `Diagnostics`、`Multiplayer`、`Extras` | 诊断、状态协议、LAN、联机适配、音频及皮肤包 |
| `SelfTest` | 不使用用户游戏目录的隔离回归测试 |

本次交付是可运行且有上述覆盖边界的版本，不是“已经 100% 复现全部 PCL 功能”的完成声明。
