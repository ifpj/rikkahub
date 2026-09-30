# Shell MCP

这是一个把运行主机的交互式 Shell 通过 HTTP 暴露为 MCP 工具的轻量 Rust 服务端，与特定客户端或终端应用无关。协议层使用官方 Rust MCP SDK `rmcp`，提供两个和 Codex Shell 对齐的工具：

- `exec_command`
- `write_stdin`

服务端自行创建 PTY 并管理一次性 Bash 命令进程，不依赖 tmux，也不写会话状态或命令脚本文件。当前 PTY 后端适用于 Android/Linux 等 Unix 环境，运行主机只需要安装 `bash`；Windows 原生环境不能执行 PTY 命令。服务重启后旧的 `session_id` 不可续接。

## 启动与连接

在 Termux 上可以先运行 `pkg install bash`；其他 Linux 环境使用系统的包管理器安装 Bash。

将 `shell-mcp` 放到 `PATH` 中后启动：

```bash
shell-mcp --port 38741 --token '替换为随机长字符串'
```

服务地址：

```text
http://127.0.0.1:38741/mcp
```

在 MCP 客户端中选择 **Streamable HTTP**，填入上面的地址，并增加请求头：

```text
Authorization: Bearer 替换为随机长字符串
```

服务端同时接受 `rmcp` 的无状态 `2026-07-28` 请求和旧版有状态连接；客户端不需要添加 Shell 专用请求头。`exec_command` 每次创建独立的 PTY 进程，并返回随机的 `session_id`。后续 `write_stdin` 必须带上该 ID，服务端不会根据 MCP 连接自动选择会话。该 ID 应当像访问凭据一样保管。

工具结果默认只有清理过 ANSI 控制序列和回车刷新行的 `stdout`。需要在客户端保留终端颜色时，可在 MCP HTTP 请求中加入 `X-Shell-Mcp-Raw-Stdout: 1`；结果会额外包含 `raw_stdout`。两字段来自同一次增量读取；`raw_stdout` 适合界面展示，不应发送给模型。RikkaHub 的每个 MCP 服务配置可单独开启此功能，默认关闭。

这里的隔离是**会话路由**，不是不同客户端之间的安全沙箱：持有同一个服务端 Token 和 `session_id` 的调用方可以续接同一 Shell；有 Shell 执行权限的调用方也能访问服务进程所在的用户环境。需要安全隔离时，应使用不同的系统用户或独立服务，而不是共享此端点。

## 构建

在桌面环境验证：

```bash
cargo build --release
```

Termux arm64 目标可以使用 Android NDK 交叉编译，例如：

```bash
cargo install cargo-ndk
cargo ndk -t arm64-v8a build --release
```

然后将 `target/aarch64-linux-android/release/shell-mcp` 复制到 Termux 的 `$PREFIX/bin/`。

## 说明

- 服务默认只监听 `127.0.0.1`。
- 旧版有状态 MCP 连接的 `Mcp-Session-Id` 在连续闲置 1 天后失效；它与下述 Shell 命令会话的超时分别计算。
- 建议始终配置随机 Token；未配置 Token 时程序会打印警告。
- `write_stdin` 的空 `chars` 用于继续等待和轮询输出。
- `yield_time_ms` 是最长等待时间；有新输出或命令结束时会提前返回。
- `interrupt`、`close_stdin`、`terminate` 和 `rows`/`columns` 与 Workspace Shell 的语义一致。
- 会话由服务进程在内存中管理；服务正常停止时会终止仍在运行的命令。
- `timeout_ms` 是**空闲超时**：每次轮询或输入都会续期，只要会话还在被使用就不会被击杀；默认 10 分钟。超时击杀以 `exit_code: 124` 加 `timed_out: true` 返回。
- 每个 `exec_command` 会话只对应一条命令。命令完成后可以继续用空 `chars` 读取最终状态，但不能通过 `write_stdin` 执行新命令；下一条命令请重新调用 `exec_command`。
- 终端输出仅保存在内存中，最多保留最近 256 KiB。超过上限的旧输出会被丢弃；单次返回的输出过长时也会标记 `truncated`。
- 已完成且闲置超过 30 分钟的会话会从内存中自动回收。服务重启后旧会话不可续接，也不会再写入 `~/.cache/shell-mcp/`。
- `close_stdin`（Ctrl+D）只应在运行中的程序等待 EOF 时使用；命令完成后不会再接受新输入。
