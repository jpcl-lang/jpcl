// main.rs
//! The `jpcl` command. Only `--version` works until the port lands.

use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) == Some("--version") {
        println!("jpcl {}", jpcl::VERSION);
        return ExitCode::SUCCESS;
    }
    eprintln!("jpcl: the Rust port is not implemented yet");
    ExitCode::from(2)
}
