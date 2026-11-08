# Know Engine

Java 应用运行在 Windows 本机，中间件运行在现有云服务器。模型统一调用百炼 API。Windows 通过 SSH 转发连接数据库、缓存、索引和对象存储，浏览器访问本机 `8009`。

## 启停

在 Windows PowerShell 5.1 或 PowerShell 7 中执行。

```powershell
Set-Location D:\IdeaProject\know-engine
.\scripts\KnowEngine.ps1 start
.\scripts\KnowEngine.ps1 status
.\scripts\KnowEngine.ps1 stop

.\scripts\DeepHelp.ps1 start
.\scripts\DeepHelp.ps1 status
.\scripts\DeepHelp.ps1 stop
```

- Know Engine 启动时先启动云端 Elasticsearch、S3 和已安装的 MinerU，然后启动 SSH 转发和本机 Java。停止时先关闭 Java 和转发，再停专用服务。数据卷和 MinerU 模型缓存保留。
- DeepHelp 的脚本管理现有四个应用单元及其专用 PostgreSQL、Milvus、etcd 和观测容器。开始时按依赖顺序恢复并核对健康状态，停止时先停止写入进程。脚本作用于当前运行状态。
- 共用 MySQL 和 Redis 独立于两个项目的停机操作。连接借用 DeepHelp 已登记的 SSH 主机、密钥路径和主机指纹。`.local/credentials.properties` 和 `.local/cloud.properties` 保存私有配置。
- 从 IntelliJ IDEA 启动 Java 时，先执行 `.\scripts\KnowEngine.ps1 start -MiddlewareOnly`。项目 SDK 使用 `.local/tools/jdk-21/jdk-21.0.13+9`，工作目录使用项目根。使用脚本停云端前，先在 IDEA 中停止自己启动的 Java 进程。

## 本机入口

| 用途 | Windows 地址 | 云端服务 |
| --- | --- | --- |
| Java 和前端 | `127.0.0.1:8009` | Java 在 Windows 运行 |
| MySQL | `127.0.0.1:23306/know_engine` | MySQL 8.4.11，复用现有实例，独立库和账号 |
| Redis | `127.0.0.1:26379` | Redis 8.2.8，独立 ACL 和 `know-engine:` 键空间 |
| Elasticsearch | `http://127.0.0.1:19200` | 8.18.6 单节点，512 MiB JVM 堆，容器内存上限 1.25 GiB |
| S3 | `http://127.0.0.1:19090` | 独立 SeaweedFS 4.48 实例，桶 `know-engine`，沿用 MinIO Java SDK |
| PDF/Word 解析 | `http://127.0.0.1:18000` | 云端 MinerU 3.4.5，`POST /file_parse` 返回 Markdown 与图片 ZIP，独立 systemd 服务，SSH 转发接入。 |

[文档管理](http://127.0.0.1:8009/document.html) · [上传](http://127.0.0.1:8009/upload.html) · [对话](http://127.0.0.1:8009/chat.html) · [评测](http://127.0.0.1:8009/eval-report.html)

对话使用 `qwen3.8-flash`，向量使用 1536 维的 `text-embedding-v4`，重排使用 `qwen3.7-text-rerank`。密钥复用 DeepHelp 的百炼配置。旧版 BGE 文件和 ONNX 依赖已移除。 [百炼文本排序 API](https://help.aliyun.com/zh/model-studio/text-rerank-api)

云部署源文件位于 `infra/cloud`，远端运行目录是 `/srv/know-engine`。两套专用容器保留各自命名的数据卷。

## 文档从上传到生效

新文档和新版本都在上传表单里选择切片策略，一次提交。「按文件类型自动选择」由服务端决定，页面关闭后后台仍继续处理。

| 文件类型 | 默认切片策略 |
| --- | --- |
| PDF / Word | 按一级、二级标题切片，最长 900 字符，重叠 120 字符 |
| Markdown | 按一级到三级标题切片，最长 900 字符，重叠 120 字符 |
| TXT | 按长度切片，最长 600 字符，重叠 80 字符 |
| CSV / Excel | 固定按表头和数据行切片 |

按标题切片时，长章节会保留完整父块供问答回填；没有可用标题时按长度切片。

1. **同步接收**：验证文件名和策略、计算文件哈希、预登记文档和版本，将文件写入 SeaweedFS。保存成功后把版本标为 `UPLOADED`，返回 HTTP `202` 和文档、版本 ID。原文件写入期间版本是 `UPLOADING`，后台不会读取未保存完成的文件。
2. **异步构建**：Java 后台扫描 MySQL 里的待处理版本，依次解析文件、切片并写入 `knowledge_segment`、调用嵌入 API 并写入 ES。状态和切片策略存在 MySQL；重启后可以从已完成阶段继续。
3. **异步发布**：刷新 ES 并按版本核对可检索切片数。通过后将索引标为 `READY`；若此期间生效版本未发生切换，同一事务把 `active_version_id` 指向新版。失败时原有生效版继续服务。上传页和版本列表都会刷新进度，升级前没有构建参数的版本可在列表里「补交构建」。

| 位置 | 负责什么 |
| --- | --- |
| `knowledge_document.active_version_id` | 问答当前使用的版本表主键 |
| `knowledge_document.publish_revision` | 发布与删除操作的修订号。普通标题、描述编辑使用 `lock_version` |
| `knowledge_document_version.build_status` | 原文件、解析和分块已完成到哪一步 |
| `knowledge_document_version.index_status` | ES 索引尚未提交、待构建或已就绪 |
| `knowledge_document_version.base_publish_revision` | 提交构建时看到的发布修订号。用户在构建期间手动切换版本时，晚完成的构建只进入“索引就绪” |
| `knowledge_segment.version_id / chunk_id` | 分块属于哪个版本。ES 以同一个 `chunk_id` 作为记录 ID，索引重试覆盖写入 |

后台使用单实例定时扫描。构建失败时，版本列表展示失败阶段，可以点“重试”。已经切好的分块会在索引重试时复用。原文件、解析结果和图片位于各自版本目录。分块列表用于查看，修改内容走上传新版本。两个检索通道在同一轮问答内共用一次 MySQL 生效版本查询。

新库使用 `src/main/resources/sql/schema.sql` 初始化。旧库先停止旧 Java 进程，再用 MySQL 管理员账号在 `know_engine` 库执行一次 `src/main/resources/sql/upgrade-document-versions.sql`。迁移保留分块 ID，并让新进程按现有分块重建索引。ES 的 `know-engine-chunks` 别名是新代码的读写入口。已有历史 URL 转换成对象键，脚本默认桶名为 `know-engine`。

## MinerU 解析服务

使用 MinerU **3.4.5**，固定与现有 Java multipart `/file_parse` 协议兼容的版本。4.x 已变更服务协议，升级时需同步评估 Java 适配。服务代码和依赖位于云端 `/opt/know-engine-mineru`，不在 Windows 安装解析模型。

- 服务名：`know-engine-mineru.service`；云端仅监听 `127.0.0.1:18000`；本机经同端口 SSH 转发访问。[API 文档](http://127.0.0.1:18000/docs)。
- 本机 `.local/cloud.properties` 设置 `KNOW_ENGINE_MINERU_URL=http://127.0.0.1:18000`，由 `application.yml` 的 `file.parse.api.url` 读取。
- Python 3.12、CPU 版 PyTorch 2.9.1、`mineru[pipeline]`，不安装 CUDA、VLM 服务或 Gradio。PDF 使用 pipeline，DOCX 使用 MinerU 自带的 Office 解析。
- 单任务、每次处理一页，CPU 上限 2 核、内存上限 4.5GiB、swap 上限 1GiB。普通文本和表格解析保留；公式识别关闭，以控制这台 4 核 8GB 服务器的内存消耗。
- 首次 PDF 解析从 ModelScope 下载实际需要的模型，缓存在服务专属目录。服务启动健康检查不代表模型已下载或文档解析已成功。
- Java 将 MinerU 解出的图片按字节传给视觉模型生成描述，对象存储地址留给本机浏览器。单张图片描述失败时，仍保留原图链接及已有说明，继续处理正文。
- 部署源为 `install-mineru.sh`、`provision-mineru.sh`、`know-engine-mineru.service`。将三者放到同一个远端目录后，用 root 执行 `bash provision-mineru.sh`；依赖清单保存到 `/opt/know-engine-mineru/requirements.lock.txt`。
- 诊断命令：`sudo systemctl status know-engine-mineru`、`sudo journalctl -u know-engine-mineru -n 80 --no-pager`。日常启动和停止仍使用本项目的 `KnowEngine.ps1`。
