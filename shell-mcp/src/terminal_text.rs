/// Produces model-friendly text from the same PTY bytes returned as raw output.
/// Escape/UTF-8 state survives poll boundaries; carriage-return updates replace
/// the current, not-yet-returned line instead of producing progress-line spam.
#[derive(Default)]
pub struct TerminalCleaner {
    escape: EscapeState,
    pending_utf8: Vec<u8>,
    line: String,
    pending_cr: bool,
    csi_params: String,
}

#[derive(Default)]
enum EscapeState {
    #[default]
    Ground,
    Escape,
    EscapeIntermediate,
    Csi,
    Osc,
    OscEscape,
    String,
    StringEscape,
}

impl TerminalCleaner {
    pub fn feed(&mut self, bytes: &[u8], finished: bool) -> String {
        self.pending_utf8.extend_from_slice(bytes);
        let mut decoded = String::new();
        let mut offset = 0;
        while offset < self.pending_utf8.len() {
            let remaining = &self.pending_utf8[offset..];
            match std::str::from_utf8(remaining) {
                Ok(text) => {
                    decoded.push_str(text);
                    offset = self.pending_utf8.len();
                }
                Err(error) => {
                    let valid = error.valid_up_to();
                    decoded.push_str(
                        std::str::from_utf8(&remaining[..valid]).expect("valid UTF-8 prefix"),
                    );
                    offset += valid;
                    if let Some(invalid) = error.error_len() {
                        decoded.push('\u{FFFD}');
                        offset += invalid;
                    } else if finished {
                        decoded.push('\u{FFFD}');
                        offset = self.pending_utf8.len();
                    } else {
                        break;
                    }
                }
            }
        }
        self.pending_utf8.drain(..offset);

        let mut output = String::new();
        for character in decoded.chars() {
            self.feed_char(character, &mut output);
        }
        if finished {
            self.escape = EscapeState::Ground;
            self.pending_cr = false;
        }
        if !self.pending_cr && !self.line.is_empty() {
            output.push_str(&self.line);
            self.line.clear();
        }
        output
    }

    fn feed_char(&mut self, character: char, output: &mut String) {
        self.escape = match std::mem::take(&mut self.escape) {
            EscapeState::Ground => match character {
                '\u{1B}' => EscapeState::Escape,
                '\u{009B}' => EscapeState::Csi,
                '\u{009D}' => EscapeState::Osc,
                '\u{0090}' | '\u{009F}' => EscapeState::String,
                '\r' => {
                    self.pending_cr = true;
                    EscapeState::Ground
                }
                '\n' => {
                    output.push_str(&self.line);
                    output.push('\n');
                    self.line.clear();
                    self.pending_cr = false;
                    EscapeState::Ground
                }
                '\u{08}' => {
                    if self.pending_cr {
                        self.line.clear();
                        self.pending_cr = false;
                    }
                    self.line.pop();
                    EscapeState::Ground
                }
                '\t' => {
                    self.push_visible('\t');
                    EscapeState::Ground
                }
                control if control.is_control() => EscapeState::Ground,
                visible => {
                    self.push_visible(visible);
                    EscapeState::Ground
                }
            },
            EscapeState::Escape => match character {
                '[' => {
                    self.csi_params.clear();
                    EscapeState::Csi
                }
                ']' => EscapeState::Osc,
                'P' | '_' | '^' | 'X' => EscapeState::String,
                '(' | ')' | '*' | '+' | '-' | '.' | '/' | '#' | '%' | ' ' | 'O' => {
                    EscapeState::EscapeIntermediate
                }
                _ => EscapeState::Ground,
            },
            EscapeState::EscapeIntermediate => EscapeState::Ground,
            EscapeState::Csi => {
                if ('@'..='~').contains(&character) {
                    if character == 'K' && (self.pending_cr || self.csi_params == "2") {
                        self.line.clear();
                        self.pending_cr = false;
                    }
                    self.csi_params.clear();
                    EscapeState::Ground
                } else {
                    if self.csi_params.len() < 128 {
                        self.csi_params.push(character);
                    }
                    EscapeState::Csi
                }
            }
            EscapeState::Osc => match character {
                '\u{07}' | '\u{009C}' => EscapeState::Ground,
                '\u{1B}' => EscapeState::OscEscape,
                _ => EscapeState::Osc,
            },
            EscapeState::OscEscape => {
                if character == '\\' {
                    EscapeState::Ground
                } else {
                    EscapeState::Osc
                }
            }
            EscapeState::String => {
                if character == '\u{009C}' {
                    EscapeState::Ground
                } else if character == '\u{1B}' {
                    EscapeState::StringEscape
                } else {
                    EscapeState::String
                }
            }
            EscapeState::StringEscape => {
                if character == '\\' {
                    EscapeState::Ground
                } else {
                    EscapeState::String
                }
            }
        };
    }

    fn push_visible(&mut self, character: char) {
        if self.pending_cr {
            self.line.clear();
            self.pending_cr = false;
        }
        self.line.push(character);
        if self.line.len() > 256 * 1024 {
            let cutoff = (64 * 1024..self.line.len())
                .find(|&index| self.line.is_char_boundary(index))
                .unwrap_or(self.line.len());
            self.line.drain(..cutoff);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_colors_and_osc_sequences() {
        let mut cleaner = TerminalCleaner::default();
        assert_eq!(
            cleaner.feed(b"\x1b[31mred\x1b[0m \x1b]0;title\x07text\n", false),
            "red text\n"
        );
    }

    #[test]
    fn erase_to_end_does_not_discard_existing_text() {
        let mut cleaner = TerminalCleaner::default();
        assert_eq!(cleaner.feed(b"hello\x1b[K\n", false), "hello\n");
        assert_eq!(cleaner.feed(b"stale\r\x1b[Kfresh\n", false), "fresh\n");
        assert_eq!(cleaner.feed(b"\x1b(Bnormal\n", false), "normal\n");
    }

    #[test]
    fn collapses_carriage_return_progress_across_polls() {
        let mut cleaner = TerminalCleaner::default();
        assert_eq!(cleaner.feed(b"10%\r", false), "");
        assert_eq!(cleaner.feed(b"20%\r\x1b[2K", false), "");
        assert_eq!(cleaner.feed(b"done\n", false), "done\n");
        assert_eq!(cleaner.feed(b"hello\r\n", false), "hello\n");
    }

    #[test]
    fn preserves_utf8_and_escape_state_across_chunks() {
        let mut cleaner = TerminalCleaner::default();
        let bytes = "你好".as_bytes();
        assert_eq!(cleaner.feed(&bytes[..2], false), "");
        assert_eq!(cleaner.feed(&bytes[2..], false), "你好");
        assert_eq!(cleaner.feed(b"\x1b[3", false), "");
        assert_eq!(cleaner.feed(b"2mgreen", false), "green");
    }

    #[test]
    fn flushes_last_line_on_completion() {
        let mut cleaner = TerminalCleaner::default();
        assert_eq!(cleaner.feed(b"last\r", false), "");
        assert_eq!(cleaner.feed(b"", true), "last");
    }
}
