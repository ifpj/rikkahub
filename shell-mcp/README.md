# Shell MCP

这是一个把运行主机的交互式 Shell 通过 HTTP 暴露为 MCP 工具的轻量 Rust 服务端，与特定客户端或终端应用无关。协议层使用官方 Rust MCP SDK `rmcp`，提供两个和 Codex Shell 对齐的工具：

- `exec_command`
- `write_stdin`

服务端通过独立的 tmux socket 管理交互式命令会话。运行主机需要安装 `tmux` 和 `bash`。

每个新会话先在 tmux 中切换到 Bash，再用一次性 Bash 进程运行命令。因此即使默认 Shell 是 fish，命令也会在 Bash 中执行；命令结束后不会留下可执行下一条命令的提示符。

## 启动与连接

在 Termux 上可以先运行 `pkg install tmux bash`；其他 Linux 环境使用系统的包管理器安装它们。

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

服务端同时接受 `rmcp` 的无状态 `2026-07-28` 请求和旧版有状态连接；客户端不需要添加 Shell 专用请求头。`exec_command` 每次创建独立的 tmux 会话，并返回随机的 `session_id`。后续 `write_stdin` 必须带上该 ID，服务端不会根据 MCP 连接自动选择会话。该 ID 与 tmux 会话名无关，应当像访问凭据一样保管。

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
- 建议始终配置随机 Token；未配置 Token 时程序会打印警告。
- `write_stdin` 的空 `chars` 用于继续等待和轮询输出。
- `yield_time_ms` 是最长等待时间；有新输出或命令结束时会提前返回。
- `interrupt`、`close_stdin`、`terminate` 和 `rows`/`columns` 与 Workspace Shell 的语义一致。
- 会话通过 `tmux -L shell-mcp` 隔离，用户仍可以在主机终端中手动 attach 会话。
- `timeout_ms` 是**空闲超时**：每次轮询或输入都会续期，只要会话还在被使用就不会被击杀；默认 10 分钟。超时击杀以 `exit_code: 124` 加 `timed_out: true` 返回。
- 每个 `exec_command` 会话只对应一条命令。命令完成后可以继续用空 `chars` 读取最终状态，但不能通过 `write_stdin` 执行新命令；下一条命令请重新调用 `exec_command`。命令退出后 tmux 保留只读窗格，以便读取末尾输出，不会把迟到的输入当作 Shell 命令执行。
- 会话状态持久化在 `~/.cache/shell-mcp/state/`，服务进程重启后已存在的会话仍可续接。
- 已完成且闲置超过 30 分钟的会话会被自动回收（tmux 会话 + 脚本目录 + 状态文件），不再泄漏。
- tmux `history-limit` 提升到 50000，可避免常见长输出被默认 2000 行缓冲驱逐；若两次轮询之间输出超过 50000 行，超出部分仍可能丢失。
- `close_stdin`（Ctrl+D）只应在运行中的程序等待 EOF 时使用；命令完成后不会再接受新输入。
