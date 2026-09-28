# RikkaHub Termux MCP

这是一个在 Termux 中运行的轻量 Rust MCP Server。它只提供两个和 Codex Shell 对齐的工具：

- `termux_exec_command`
- `termux_write_stdin`

服务端通过独立的 tmux socket 管理持久 shell 会话，不依赖 RikkaHub 直接访问 Termux 的内部 `TerminalSession`。

## Termux 侧准备

```bash
pkg install tmux
```

将 `rikkahub-termux-mcp` 放到 `$PREFIX/bin/` 后启动：

```bash
rikkahub-termux-mcp --port 38741 --token '替换为随机长字符串'
```

服务地址：

```text
http://127.0.0.1:38741/mcp
```

RikkaHub 的 MCP 设置中选择 **Streamable HTTP**，填入上面的地址，并增加请求头：

```text
Authorization: Bearer 替换为随机长字符串
```

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

然后将 `target/aarch64-linux-android/release/rikkahub-termux-mcp` 复制到 Termux 的 `$PREFIX/bin/`。

## 说明

- 服务默认只监听 `127.0.0.1`。
- 建议始终配置随机 Token；未配置 Token 时程序会打印警告。
- `termux_write_stdin` 的空 `chars` 用于继续等待和轮询输出。
- `interrupt`、`close_stdin`、`terminate` 和 `rows`/`columns` 与 Workspace Shell 的语义一致。
- 会话通过 `tmux -L rikkahub` 隔离，用户仍可以在 Termux 中手动 attach 会话。
