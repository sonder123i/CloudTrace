# 云迹（CloudTrace）应用内更新

更新链路与参考项目保持一致的安全模型：

1. 双源签名清单（GitHub Pages 主 + Gitee raw 备）；
2. ECDSA P-256 / SHA-256 验签，公钥钉在 `app/src/main/assets/app-update-keys.json`；
3. 多镜像下载、Range 断点续传；
4. 下载完成校验 size / SHA-256 / 包名 / versionCode / versionName / APK 签名证书；
5. 用户确认后通过 FileProvider 拉起系统安装器。

## 1. 一次性配置

### 1.1 生成更新签名密钥

```powershell
powershell -ExecutionPolicy Bypass -File scripts/init_update_signing.ps1
```

私钥默认生成在仓库外：

```
C:\Users\<你>\projects\gogogo-update-signing\update-signing-key.pem
```

**立刻离线备份。** 私钥丢失后无法再签发可被旧版本信任的更新清单。
脚本会打印一段公钥 JSON，把它合并进：

```
app/src/main/assets/app-update-keys.json
```

当前 keyId：`gogogo-update-2026-11`。轮换密钥时新增一个 keyId，旧公钥先保留。

### 1.2 配置 GitHub Pages

在仓库 `Settings → Pages` 选择：

- Source: `Deploy from a branch`
- Branch: `master`
- Folder: `/docs`

发布后的清单地址形如：

```
https://<owner>.github.io/CloudTrace/update/stable.json
```

Gitee 备用源直接读 main 分支：

```
https://gitee.com/<owner>/cloud-traces/raw/master/docs/update/stable.json
```

如果实际账号/域名不同，不需要改代码，在 `local.properties` 覆盖：

```properties
GOGOGO_UPDATE_URL=https://<你的域名>/CloudTrace/update/stable.json
GOGOGO_UPDATE_BACKUP_URL=https://gitee.com/<owner>/cloud-traces/raw/master/docs/update/stable.json
GOGOGO_UPDATE_ALLOWED_HOSTS=<你的域名>,gitee.com,foruda.gitee.com,github.com,objects.githubusercontent.com,release-assets.githubusercontent.com
```

## 2. 发版

1. 修改 `app/build.gradle` 的 `versionCode` / `versionName`（必须单调递增）。
2. 构建 arm64 release：

   ```bash
   ./gradlew :app:assembleRelease
   ```

   产物：`app/build/outputs/apk/release/CloudTrace_<version>_arm64-v8a_release.apk`

3. 上传 APK 到 GitHub Release / Gitee Release，拿到两个下载地址。
4. 写更新日志，例如 `release-notes/v1.14.4.md`。
   这份文件会放进签名清单，并在更新弹窗的「本次更新」里展示。请按用户视角写：
   先说用户能感知到什么，不写类名、函数名和排查过程；版本号标题和「校验」段会被
   `UpdateNotesFormatter` 裁掉，其余内容直接给用户看。
5. 生成签名清单：

   ```bash
   python scripts/make_update_manifest.py \
     --apk app/build/outputs/apk/release/CloudTrace_1.13.0_arm64-v8a_release.apk \
     --mirror gh="GitHub Release"="https://github.com/<owner>/CloudTrace/releases/download/v1.13.0/CloudTrace_1.13.0_arm64-v8a_release.apk" \
     --mirror gitee="Gitee 附件"="https://gitee.com/<owner>/cloud-traces/releases/download/v1.13.0/CloudTrace_1.13.0_arm64-v8a_release.apk" \
     --notes-file release-notes/v1.13.0.md \
     --key-file "$GOGOGO_UPDATE_SIGNING_KEY" \
     --out docs/update/stable.json
   ```

6. 提交并推送 `docs/update/stable.json`；Pages 更新后主源生效。
7. Gitee 同步 `docs/update/stable.json` 与 Release 附件。

## 3. 验证

1. 安装旧版本（versionCode 小于清单）。
2. 进入「体检设置」→「应用更新」，应显示新版本号与更新说明。
3. 点「下载更新」：通知栏出现进度，可暂停/继续；退出 App 再回来应恢复进度。
4. 下载完成后点「安装」，系统安装器应正常拉起。
5. 覆盖安装后，路线、定时计划、历史记录、设置应保留。

## 4. 安全约定

- 更新清单和 APK 地址只允许 HTTPS、443 端口；host 必须在允许列表内。
- 客户端拒绝 localhost、回环、私有网段、链路本地和保留地址。
- APK 必须与当前安装包使用同一签名证书，防止被替换成其他应用。
- 私钥不进入仓库、不进入 APK、不打印到日志；只通过 `--key-file` / 环境变量传入。
