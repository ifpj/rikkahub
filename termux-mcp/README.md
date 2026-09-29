# RikkaHub Shell MCP

这是一个通过 HTTP 暴露交互式 Shell 的轻量 Rust MCP Server。协议层使用官方 Rust MCP SDK `rmcp`，只提供两个和 Codex Shell 对齐的工具：

- `exec_command`
- `write_stdin`

服务端通过独立的 tmux socket 管理持久 shell 会话，不依赖 RikkaHub 直接访问 Termux 的内部 `TerminalSession`。

每个新会话会先在 tmux 中执行一次 `exec bash`，因此即使 Termux 的默认 Shell 是 fish，后续工具调用仍会复用同一个 Bash 会话和原有环境变量。

## Termux 侧准备

```bash
pkg install tmux
```

将 `rikkahub-shell-mcp` 放到 `$PREFIX/bin/` 后启动：

```bash
rikkahub-shell-mcp --port 38741 --token '替换为随机长字符串'
```

服务地址：

```text
http://127.0.0.1:38741/mcp
```

RikkaHub 的 MCP 设置中选择 **Streamable HTTP**，填入上面的地址，并增加请求头：

```text
Authorization: Bearer 替换为随机长字符串
```

服务端同时接受 `rmcp` 的无状态 `2026-07-28` 请求和旧版有状态连接；客户端不需要添加 Shell 专用请求头。`exec_command` 每次创建独立的 tmux 会话，并返回随机的 `session_id`。后续 `write_stdin` 必须带上该 ID，服务端不会根据 MCP 连接自动选择会话。该 ID 与 tmux 会话名无关，应当像访问凭据一样保管。

这里的隔离是**会话路由**，不是不同客户端之间的安全沙箱：持有同一个服务端 Token 和 `session_id` 的调用方可以续接同一 Shell；有 Shell 执行权限的调用方本身也能访问同一个 Termux 用户环境。需要安全隔离时，应使用不同的 Termux 用户/实例或独立服务，而不是共享此端点。

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

然后将 `target/aarch64-linux-android/release/rikkahub-shell-mcp` 复制到 Termux 的 `$PREFIX/bin/`。

## 说明

- 服务默认只监听 `127.0.0.1`。
- 建议始终配置随机 Token；未配置 Token 时程序会打印警告。
- `write_stdin` 的空 `chars` 用于继续等待和轮询输出。
- `interrupt`、`close_stdin`、`terminate` 和 `rows`/`columns` 与 Workspace Shell 的语义一致。
- 会话通过 `tmux -L rikkahub` 隔离，用户仍可以在 Termux 中手动 attach 会话。
- `timeout_ms` 是**空闲超时**：每次轮询或输入都会续期，只要会话还在被使用就不会被击杀；默认 10 分钟。超时击杀以 `exit_code: 124` 加 `timed_out: true` 返回。
- 在已完成的会话上通过 `write_stdin` 发送新命令时，服务端会把命令和独立的退出码标记作为同一条 Shell 命令提交，后续命令报告自己的 `exit_code`，不会继承上一条命令的陈旧标记；续发命令在常驻 Bash 中执行，其对工作目录和环境变量的修改会留给后续续发命令。
- 会话状态持久化在 `~/.cache/rikkahub-shell-mcp/state/`，服务进程重启后已存在的会话仍可续接（修复重启后 `session_id is not known`）。
- 已完成且闲置超过 30 分钟的会话会被自动回收（tmux 会话 + 脚本目录 + 状态文件），不再泄漏。
- tmux `history-limit` 提升到 50000，可避免常见长输出被默认 2000 行缓冲驱逐；若两次轮询之间输出超过 50000 行，超出部分仍可能丢失。
- `close_stdin`（Ctrl+D）只应在程序等待 EOF 时使用；在空闲 shell 提示符下发送会退出常驻 shell 并终止会话。
