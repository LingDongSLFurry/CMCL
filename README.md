# CMCL · Cairn / 石栈（Java 终端版）

独立实现的 Minecraft Java 启动器，以字符工作区和命令输入为核心。没有标准图形启动页面，不仿制 PCL 界面。原版游戏、资源和 Java 默认使用 Mojang 官方源，也可通过指令配置、检查和切换自定义下载源。

本项目目前为 **0.1.1 预发布**。已经实现和验证主要下载、实例、加载器、Mod 与进程管理链路，但**尚未达到与 PCL 所有功能、兼容特例和专属服务完全等价**。完整边界见[功能与实现对照](docs/PCL功能与实现对照.md)，已实测项目见[验证报告](docs/验证报告.md)。

## 下载和运行

[发行版与附件](https://github.com/LingDongSLFurry/CMCL/releases/tag/cairn-v0.1.1)

启动器需要 Java 17 或更新版本。下载 `cairn.jar`：

```text
java -jar cairn.jar
```

完整发行包内的 Windows `Start.cmd`、PowerShell `Start.ps1` 和 Linux/macOS `start.sh` 也可启动。默认数据目录为 JAR 旁的 `cairn-data`，可用 `--home` 指定其他磁盘。

首次使用可输入 `setup`，或执行：

```text
account offline Player
install 1.20.1 survival
instance use survival
play
```

游戏本身正常显示 Minecraft 窗口；启动器只显示终端内容。

0.1.1 修复了 Windows 代码页 936 下的乱码。直接运行 JAR 会匹配控制台编码，重定向输出默认 UTF-8；`Start.cmd` / `Start.ps1` 使用 UTF-8 和兼容边框，避免旧 CMD 显示颜色控制码。可用 `--encoding UTF-8` 或 `--encoding GBK` 显式指定输出编码。

## 功能

- 字符工作区、数字菜单、指令、`&&` / `;`、UTF-8 脚本、别名、历史、JSON 输出与后台任务。
- Mojang 版本清单、客户端/服务端、资源、本地库、断点续传、哈希校验、补全与官方 Java。
- 自定义下载源：清单、元数据、客户端、库、Assets、Java 分别配置，源间元数据缓存隔离，默认不添加第三方镜像预设。
- 独立实例、收藏/隐藏、复制、重命名、回收恢复、外部游戏目录复制导入。
- 离线、微软设备代码、Yggdrasil 账户；皮肤下载、上传、URL 换肤和本地皮肤包。
- Fabric、Quilt、Forge、NeoForge、OptiFine、LiteLoader；加载器使用各自的上游。
- Modrinth/CurseForge 资源、必需依赖、版本更新、Mod 启停及元数据。
- 常见整合包导入、Cairn 整合包导出、无令牌启动脚本、独立游戏监护、日志和规则诊断。
- 服务器状态、LAN 发现及自备 EasyTier 的联机适配。

微软登录需要自己的合资格公共客户端 ID；CurseForge 需要自己的 API Key；第三方认证需要自备注入器；联机适配需要 EasyTier 与节点。没有内置其他项目的密钥或 PCL 私有服务。

## 指令示例

```text
versions list --type release
loader install fabric 1.20.1 0.16.14 fabricGame
content install sodium --instance fabricGame
content update fabricGame
pack export fabricGame my-pack.zip
play fabricGame --dry-run
logs fabricGame --follow
doctor fabricGame --deep
help all
```

自定义下载源（将示例域名替换成实际地址）：

```text
source add myMirror https://mirror.example.org
source edit myMirror --libraries https://mirror.example.org/maven
source edit myMirror --assets https://mirror.example.org/assets
source check myMirror
source use myMirror
source use official
```

`source add` 默认保留 Mojang URL 路径；各服务的前缀可能不同，需按提供方说明配置。局域网 HTTP 源可显式添加 `--allow-http`。登录请求不使用下载源路由，已有文件仍按哈希复用。

详细操作见[使用说明](docs/使用说明.md)与内置 `help all`。

## 从源码构建

仅需 JDK 17+，不依赖 Maven、Gradle 或第三方运行库。

Windows：

```powershell
.\build.ps1
```

Linux/macOS：

```sh
sh build.sh
```

产物为 `dist/cairn.jar`。构建脚本自动执行隔离回归测试。手动运行测试：

```text
java -jar dist/cairn.jar --self-test
```

源文件位于 `src/main/java/dev/cairn/`，实现结构见[实现说明](docs/实现说明.md)。本次完整实测环境为 Windows x64；其他平台代码分支存在，但尚未执行真实系统验证。

## 参考与许可

按用户指定，调查 [PCL](https://github.com/Meloong-Git/PCL) 的公开功能及实现位置；参考作者为龙腾猫跃，[赞助链接](https://meloong.com/afd/a/LTCat)。没有复制其源代码、图形页面、图片、捆绑 JAR 或私有服务配置。公开源码符号索引保留于 `docs/PCL-source-index.json`。

Cairn 自有实现采用 MIT 许可。Minecraft、PCL、加载器、运行时和社区资源遵循各自条款。详见 [LICENSE](LICENSE.txt) 和[第三方说明](docs/第三方说明.md)。
