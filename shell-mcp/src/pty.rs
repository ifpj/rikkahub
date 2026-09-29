use std::path::Path;
use tokio::process::Child;

#[cfg(unix)]
mod unix {
    use super::*;
    use std::{
        fs::File,
        io,
        os::{
            fd::{AsRawFd, FromRawFd, OwnedFd},
            unix::process::CommandExt,
        },
        sync::Arc,
    };
    use tokio::{io::unix::AsyncFd, process::Command};

    pub struct PtyProcess {
        master: Arc<AsyncFd<OwnedFd>>,
        pid: i32,
    }

    pub fn spawn(
        command: &str,
        cwd: &Path,
        rows: u16,
        columns: u16,
    ) -> Result<(PtyProcess, Child), String> {
        let size = libc::winsize {
            ws_row: rows,
            ws_col: columns,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let (mut master_fd, mut slave_fd) = (-1, -1);
        if unsafe {
            libc::openpty(
                &mut master_fd,
                &mut slave_fd,
                std::ptr::null_mut(),
                std::ptr::null(),
                &size,
            )
        } != 0
        {
            return Err(format!("cannot open PTY: {}", io::Error::last_os_error()));
        }
        let master = unsafe { File::from_raw_fd(master_fd) };
        let slave = unsafe { File::from_raw_fd(slave_fd) };
        configure_master(master.as_raw_fd())
            .map_err(|error| format!("cannot configure PTY: {error}"))?;

        let stdin = slave
            .try_clone()
            .map_err(|error| format!("cannot clone PTY: {error}"))?;
        let stdout = slave
            .try_clone()
            .map_err(|error| format!("cannot clone PTY: {error}"))?;
        let mut process = Command::new("bash");
        process
            .arg("-c")
            .arg(command)
            .current_dir(cwd)
            .stdin(stdin)
            .stdout(stdout)
            .stderr(slave)
            .kill_on_drop(true);
        if std::env::var_os("TERM").is_none() {
            process.env("TERM", "xterm-256color");
        }
        // Only async-signal-safe libc calls are allowed between fork and exec.
        unsafe {
            process.as_std_mut().pre_exec(|| {
                if libc::setsid() < 0 {
                    return Err(io::Error::last_os_error());
                }
                if libc::ioctl(libc::STDIN_FILENO, libc::TIOCSCTTY as _, 0) < 0 {
                    return Err(io::Error::last_os_error());
                }
                Ok(())
            });
        }
        let child = process
            .spawn()
            .map_err(|error| format!("cannot start bash: {error}"))?;
        let pid = child.id().ok_or("bash started without a process ID")? as i32;
        let master: OwnedFd = master.into();
        let master = AsyncFd::new(master).map_err(|error| format!("cannot watch PTY: {error}"))?;
        Ok((
            PtyProcess {
                master: Arc::new(master),
                pid,
            },
            child,
        ))
    }

    fn configure_master(fd: i32) -> io::Result<()> {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            return Err(io::Error::last_os_error());
        }
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFD, flags | libc::FD_CLOEXEC) } < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    impl PtyProcess {
        pub async fn read_chunk(&self) -> Result<Option<Vec<u8>>, String> {
            loop {
                let mut ready = self
                    .master
                    .readable()
                    .await
                    .map_err(|error| error.to_string())?;
                let mut buffer = vec![0u8; 8192];
                match ready.try_io(|fd| {
                    let count = unsafe {
                        libc::read(fd.as_raw_fd(), buffer.as_mut_ptr().cast(), buffer.len())
                    };
                    if count < 0 {
                        Err(io::Error::last_os_error())
                    } else {
                        Ok(count as usize)
                    }
                }) {
                    Ok(Ok(0)) => return Ok(None),
                    Ok(Ok(count)) => {
                        buffer.truncate(count);
                        return Ok(Some(buffer));
                    }
                    Ok(Err(error)) if error.raw_os_error() == Some(libc::EIO) => return Ok(None),
                    Ok(Err(error)) if error.kind() == io::ErrorKind::Interrupted => continue,
                    Ok(Err(error)) => return Err(format!("cannot read PTY: {error}")),
                    Err(_) => continue,
                }
            }
        }

        pub async fn write_all(&self, bytes: &[u8]) -> Result<(), String> {
            let mut remaining = bytes;
            while !remaining.is_empty() {
                let mut ready = self
                    .master
                    .writable()
                    .await
                    .map_err(|error| error.to_string())?;
                match ready.try_io(|fd| {
                    let count = unsafe {
                        libc::write(fd.as_raw_fd(), remaining.as_ptr().cast(), remaining.len())
                    };
                    if count < 0 {
                        Err(io::Error::last_os_error())
                    } else {
                        Ok(count as usize)
                    }
                }) {
                    Ok(Ok(0)) => return Err("PTY accepted zero input bytes".into()),
                    Ok(Ok(count)) => remaining = &remaining[count..],
                    Ok(Err(error)) if error.kind() == io::ErrorKind::Interrupted => continue,
                    Ok(Err(error)) => return Err(format!("cannot write PTY: {error}")),
                    Err(_) => continue,
                }
            }
            Ok(())
        }

        pub fn resize(&self, rows: u16, columns: u16) -> Result<(), String> {
            let size = libc::winsize {
                ws_row: rows,
                ws_col: columns,
                ws_xpixel: 0,
                ws_ypixel: 0,
            };
            if unsafe {
                libc::ioctl(
                    self.master.get_ref().as_raw_fd(),
                    libc::TIOCSWINSZ as _,
                    &size,
                )
            } < 0
            {
                return Err(format!("cannot resize PTY: {}", io::Error::last_os_error()));
            }
            self.signal(libc::SIGWINCH)?;
            Ok(())
        }

        pub fn interrupt(&self) -> Result<bool, String> {
            self.signal(libc::SIGINT)
        }

        pub fn terminate(&self) -> Result<bool, String> {
            self.signal(libc::SIGKILL)
        }

        fn signal(&self, signal: i32) -> Result<bool, String> {
            if unsafe { libc::kill(-self.pid, signal) } == 0 {
                return Ok(true);
            }
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::ESRCH) {
                Ok(false)
            } else {
                Err(format!("cannot signal shell process: {error}"))
            }
        }
    }
}

#[cfg(unix)]
pub use unix::{spawn, PtyProcess};

#[cfg(not(unix))]
pub struct PtyProcess;

#[cfg(not(unix))]
pub fn spawn(_: &str, _: &Path, _: u16, _: u16) -> Result<(PtyProcess, Child), String> {
    Err("PTY shell execution requires Linux or Android".into())
}

#[cfg(not(unix))]
impl PtyProcess {
    pub async fn read_chunk(&self) -> Result<Option<Vec<u8>>, String> {
        Ok(None)
    }
    pub async fn write_all(&self, _: &[u8]) -> Result<(), String> {
        Err("PTY unavailable".into())
    }
    pub fn resize(&self, _: u16, _: u16) -> Result<(), String> {
        Err("PTY unavailable".into())
    }
    pub fn interrupt(&self) -> Result<bool, String> {
        Err("PTY unavailable".into())
    }
    pub fn terminate(&self) -> Result<bool, String> {
        Err("PTY unavailable".into())
    }
}
