// main.rs
//! The `jpcl` command: check, fmt, get, to-json and from-json.

use std::collections::HashMap;
use std::fs;
use std::io::{self, Read, Write};
use std::path::Path;
use std::process::ExitCode;

use jpcl::{Object, ParseOptions, StringifyOptions, Value};

const MAIN_USAGE: &str = "usage: jpcl [-h] [--version] {check,fmt,get,to-json,from-json} ...

Work with .jp configuration files.

commands:
  check       validate .jp files
  fmt         reformat .jp files
  get         print one value by dotted path
  to-json     convert .jp to JSON
  from-json   convert JSON to .jp

options:
  -h, --help      show this help message and exit
  --version       show the version and exit
";

const FORMAT_HELP: &str = "  --indent N      spaces per level (default 2)
  --width N       column budget for inline arrays (default 88)
  --sort-keys     sort keys alphabetically";

struct Opt {
    long: &'static str,
    short: Option<char>,
    takes_value: bool,
}

const fn flag(long: &'static str, short: Option<char>) -> Opt {
    Opt {
        long,
        short,
        takes_value: false,
    }
}

const fn valued(long: &'static str, short: Option<char>) -> Opt {
    Opt {
        long,
        short,
        takes_value: true,
    }
}

type Run = fn(&mut Cli, &Flags, &[String]) -> Result<u8, CliError>;

struct Command {
    name: &'static str,
    usage: String,
    options: Vec<Opt>,
    // Positional names; a trailing `...` marks one or more
    positionals: &'static [&'static str],
    run: Run,
}

fn format_options() -> [Opt; 3] {
    [
        valued("indent", None),
        valued("width", None),
        flag("sort-keys", None),
    ]
}

fn commands() -> Vec<Command> {
    vec![
        Command {
            name: "check",
            usage: "usage: jpcl check [-q] files...\n\n  -q, --quiet     only report errors".into(),
            options: vec![flag("quiet", Some('q'))],
            positionals: &["files..."],
            run: run_check,
        },
        Command {
            name: "fmt",
            usage: format!(
                "usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...\n\n  -w, --write     rewrite files in place\n{FORMAT_HELP}"
            ),
            options: [flag("write", Some('w'))].into_iter().chain(format_options()).collect(),
            positionals: &["files..."],
            run: run_fmt,
        },
        Command {
            name: "get",
            usage: "usage: jpcl get [-r] file path\n\n  path            e.g. SERVER_ID.config.disabled_users\n  -r, --raw       print strings unquoted".into(),
            options: vec![flag("raw", Some('r'))],
            positionals: &["file", "path"],
            run: run_get,
        },
        Command {
            name: "to-json",
            usage: "usage: jpcl to-json [-o OUTPUT] [--indent N] file\n\n  -o, --output    write to a file instead of stdout\n  --indent N      spaces per level (default 2)".into(),
            options: vec![valued("output", Some('o')), valued("indent", None)],
            positionals: &["file"],
            run: run_to_json,
        },
        Command {
            name: "from-json",
            usage: format!(
                "usage: jpcl from-json [-o OUTPUT] [--indent N] [--width N] [--sort-keys] file\n\n  -o, --output    write to a file instead of stdout\n{FORMAT_HELP}"
            ),
            options: [valued("output", Some('o'))].into_iter().chain(format_options()).collect(),
            positionals: &["file"],
            run: run_from_json,
        },
    ]
}

enum CliError {
    Usage(String),
    Io(io::Error),
}

impl From<io::Error> for CliError {
    fn from(error: io::Error) -> Self {
        CliError::Io(error)
    }
}

/// Parsed `--flag` values for one command, by long name.
#[derive(Default)]
struct Flags {
    map: HashMap<&'static str, Option<String>>,
}

impl Flags {
    fn has(&self, name: &str) -> bool {
        self.map.contains_key(name)
    }

    fn value(&self, name: &str) -> Option<&str> {
        self.map.get(name)?.as_deref()
    }

    fn integer(&self, name: &str, fallback: usize) -> Result<usize, CliError> {
        let Some(raw) = self.value(name) else {
            return Ok(fallback);
        };
        let digits_only = !raw.is_empty() && raw.bytes().all(|byte| byte.is_ascii_digit());

        match raw.parse() {
            Ok(number) if digits_only => Ok(number),
            _ => Err(CliError::Usage(format!(
                "argument --{name}: invalid non-negative int value: '{raw}'"
            ))),
        }
    }

    fn format(&self) -> Result<StringifyOptions, CliError> {
        Ok(StringifyOptions {
            indent: self.integer("indent", 2)?,
            width: self.integer("width", 88)?,
            sort_keys: self.has("sort-keys"),
            ensure_ascii: false,
            })
    }
}

struct Cli<'a> {
    stdout: &'a mut dyn Write,
    stderr: &'a mut dyn Write,
    stdin: &'a mut dyn FnMut() -> io::Result<Vec<u8>>,
}

fn first_line(text: &str) -> &str {
    text.lines().next().unwrap_or_default()
}

impl Cli<'_> {
    /// Exit code: 0 on success, 1 when a file fails, 2 for invalid usage.
    fn run(&mut self, argv: &[String]) -> io::Result<u8> {
        let Some(name) = argv.first() else {
            writeln!(
                self.stderr,
                "{}\njpcl: error: a command is required",
                first_line(MAIN_USAGE)
            )?;
            return Ok(2);
        };

        match name.as_str() {
            "-h" | "--help" => {
                self.stdout.write_all(MAIN_USAGE.as_bytes())?;
                return Ok(0);
            }
            "--version" => {
                writeln!(self.stdout, "jpcl {}", jpcl::VERSION)?;
                return Ok(0);
            }
            _ => {}
        }

        let all = commands();
        let Some(command) = all.iter().find(|command| command.name == name) else {
            writeln!(
                self.stderr,
                "{}\njpcl: error: invalid command '{name}' (choose from check, fmt, get, to-json, from-json)",
                first_line(MAIN_USAGE),
            )?;
            return Ok(2);
        };

        let outcome = parse_args(command, &argv[1..]).and_then(|(flags, positionals)| {
            if flags.has("help") {
                writeln!(self.stdout, "{}", command.usage)?;
                return Ok(0);
            }

            check_positionals(command, &positionals)?;
            (command.run)(self, &flags, &positionals)
            });

        match outcome {
            Ok(code) => Ok(code),
            Err(CliError::Io(error)) => Err(error),
            Err(CliError::Usage(message)) => {
                writeln!(
                    self.stderr,
                    "{}\njpcl {}: error: {message}",
                    first_line(&command.usage),
                    command.name
                )?;
                Ok(2)
            }
        }
    }

    /// Read a file, or stdin when `path` is `-`. None means it failed and was reported.
    fn read_bytes(&mut self, path: &str) -> io::Result<Option<Vec<u8>>> {
        if path == "-" {
            return (self.stdin)().map(Some);
        }

        match fs::read(path) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(error) => {
                writeln!(
                    self.stderr,
                    "{path}: cannot read file: {}",
                    describe(&error)
                )?;
                Ok(None)
            }
        }
    }

    /// Load and parse a `.jp` file. None means it failed and was reported.
    fn read(&mut self, path: &str) -> io::Result<Option<Object>> {
        let Some(bytes) = self.read_bytes(path)? else {
            return Ok(None);
        };

        match jpcl::parse_bytes(&bytes, &ParseOptions::default()) {
            Ok(root) => Ok(Some(root)),
            Err(error) => {
                let filename = if path == "-" { "<stdin>" } else { path };
                writeln!(self.stderr, "{}", error.with_filename(filename))?;
                Ok(None)
            }
        }
    }

    fn output(&mut self, flags: &Flags, text: &str) -> io::Result<u8> {
        let Some(path) = flags.value("output") else {
            self.stdout.write_all(text.as_bytes())?;
            return Ok(0);
        };

        if let Err(error) = fs::write(path, text) {
            writeln!(
                self.stderr,
                "{path}: cannot write file: {}",
                describe(&error)
            )?;
            return Ok(1);
        }

        Ok(0)
    }
}

fn describe(error: &io::Error) -> String {
    match error.kind() {
        io::ErrorKind::NotFound => "no such file or directory".into(),
        io::ErrorKind::PermissionDenied => "permission denied".into(),
        io::ErrorKind::IsADirectory => "is a directory".into(),
        _ => error.to_string(),
    }
}

fn find_option<'c>(
    command: &'c Command,
    long: Option<&str>,
    short: Option<char>,
) -> Option<&'c Opt> {
    const HELP: Opt = flag("help", Some('h'));

    if long == Some("help") || short == Some('h') {
        return Some(&HELP);
    }

    command.options.iter().find(|option| match long {
        Some(name) => option.long == name,
        None => short.is_some() && option.short == short,
        })
}

fn parse_args(command: &Command, argv: &[String]) -> Result<(Flags, Vec<String>), CliError> {
    let mut flags = Flags::default();
    let mut positionals = Vec::new();
    let mut options_done = false;
    let mut args = argv.iter();

    while let Some(arg) = args.next() {
        if options_done || arg.len() < 2 || !arg.starts_with('-') {
            positionals.push(arg.clone());
            continue;
        }

        if arg == "--" {
            options_done = true;
            continue;
        }

        let (option, inline_value) = if let Some(body) = arg.strip_prefix("--") {
            let (name, value) = match body.split_once('=') {
                Some((name, value)) => (name, Some(value)),
                None => (body, None),
            };
            (find_option(command, Some(name), None), value)
        } else {
            let mut chars = arg[1..].chars();
            let short = chars.next();
            let single = chars.next().is_none();
            (
                if single {
                    find_option(command, None, short)
                } else {
                    None
                },
                None,
            )
        };
        let Some(option) = option else {
            return Err(CliError::Usage(format!("unrecognized arguments: {arg}")));
        };

        if !option.takes_value {
            if let Some(value) = inline_value {
                return Err(CliError::Usage(format!(
                    "argument --{}: ignored explicit argument '{value}'",
                    option.long
                )));
            }

            flags.map.insert(option.long, None);
            continue;
        }

        let value = match inline_value {
            Some(value) => value.to_string(),
            None => args.next().cloned().ok_or_else(|| {
                CliError::Usage(format!("argument --{}: expected one argument", option.long))
                })?,
        };
        flags.map.insert(option.long, Some(value));
    }

    Ok((flags, positionals))
}

fn check_positionals(command: &Command, given: &[String]) -> Result<(), CliError> {
    let names = command.positionals;
    let variadic = names.last().is_some_and(|name| name.ends_with("..."));

    if given.len() < names.len() {
        let missing: Vec<&str> = names[given.len()..]
        .iter()
        .map(|name| name.trim_end_matches('.'))
        .collect();
        return Err(CliError::Usage(format!(
            "the following arguments are required: {}",
            missing.join(", ")
        )));
    }

    if !variadic && given.len() > names.len() {
        return Err(CliError::Usage(format!(
            "unrecognized arguments: {}",
            given[names.len()..].join(" ")
        )));
    }

    Ok(())
}

// -- commands --------------------------------------------------------------

fn run_check(cli: &mut Cli, flags: &Flags, files: &[String]) -> Result<u8, CliError> {
    let mut failures = 0;

    for path in files {
        if cli.read(path)?.is_none() {
            failures += 1;
        } else if !flags.has("quiet") {
            writeln!(cli.stdout, "ok  {path}")?;
        }
    }

    Ok(u8::from(failures > 0))
}

fn run_fmt(cli: &mut Cli, flags: &Flags, files: &[String]) -> Result<u8, CliError> {
    let format = flags.format()?;
    let mut failures = 0;

    for path in files {
        let Some(root) = cli.read(path)? else {
            failures += 1;
            continue;
        };
        let text = jpcl::stringify(&root, &format);

        if !flags.has("write") || path == "-" {
            cli.stdout.write_all(text.as_bytes())?;
            continue;
        }

        let Some(current) = cli.read_bytes(path)? else {
            failures += 1;
            continue;
        };

        if current == text.as_bytes() {
            continue;
        }

        if let Err(error) = jpcl::write_atomic(Path::new(path), text.as_bytes()) {
            writeln!(
                cli.stderr,
                "{path}: cannot write file: {}",
                describe(&error)
            )?;
            failures += 1;
            continue;
        }

        writeln!(cli.stderr, "reformatted {path}")?;
    }

    Ok(u8::from(failures > 0))
}

fn run_get(cli: &mut Cli, flags: &Flags, positionals: &[String]) -> Result<u8, CliError> {
    let Some(root) = cli.read(&positionals[0])? else {
        return Ok(1);
    };
    let path = &positionals[1];
    let Some(value) = jpcl::get_path(&root, path) else {
        writeln!(cli.stderr, "no such path: {path}")?;
        return Ok(1);
    };

    match value {
        Value::String(text) if flags.has("raw") => writeln!(cli.stdout, "{text}")?,
        _ => writeln!(cli.stdout, "{}", jpcl::to_json(value, 2))?,
    }

    Ok(0)
}

fn run_to_json(cli: &mut Cli, flags: &Flags, positionals: &[String]) -> Result<u8, CliError> {
    let indent = flags.integer("indent", 2)?;
    let Some(root) = cli.read(&positionals[0])? else {
        return Ok(1);
    };
    let json = jpcl::to_json(&Value::Object(root), indent) + "\n";
    Ok(cli.output(flags, &json)?)
}

fn run_from_json(cli: &mut Cli, flags: &Flags, positionals: &[String]) -> Result<u8, CliError> {
    let format = flags.format()?;
    let Some(bytes) = cli.read_bytes(&positionals[0])? else {
        return Ok(1);
    };
    let parsed = std::str::from_utf8(&bytes)
    .map_err(|_| "invalid JSON: not UTF-8".to_string())
    .and_then(jpcl::from_json);
    let root = match parsed {
        Ok(Value::Object(root)) => root,
        Ok(other) => {
            writeln!(
                cli.stderr,
                "the top level of a .jp document must be an object, got '{}'",
                type_name(&other),
            )?;
            return Ok(1);
        }
        Err(message) => {
            writeln!(cli.stderr, "{message}")?;
            return Ok(1);
        }
    };
    Ok(cli.output(flags, &jpcl::stringify(&root, &format))?)
}

fn type_name(value: &Value) -> &'static str {
    match value {
        Value::Null => "null",
        Value::Bool(_) => "boolean",
        Value::Integer(_) | Value::Float(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "Array",
        Value::Object(_) => "Object",
    }
}

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let stdout = io::stdout();
    let stderr = io::stderr();
    let mut stdout = stdout.lock();
    let mut stderr = stderr.lock();
    let mut stdin = || {
        let mut bytes = Vec::new();
        io::stdin().read_to_end(&mut bytes)?;
        Ok(bytes)
    };
    let mut cli = Cli {
        stdout: &mut stdout,
        stderr: &mut stderr,
        stdin: &mut stdin,
    };
    let code = match cli.run(&argv) {
        Ok(code) => code,
        // A closed stdout, as in `jpcl to-json file | head`, is not an error worth a panic
        Err(error) if error.kind() == io::ErrorKind::BrokenPipe => 0,
        Err(error) => {
            let _ = writeln!(io::stderr(), "jpcl: {error}");
            1
        }
    };
    ExitCode::from(code)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestRun {
        code: u8,
        stdout: String,
        stderr: String,
    }

    fn run(argv: &[&str], input: &str) -> TestRun {
        let argv: Vec<String> = argv.iter().map(|arg| arg.to_string()).collect();
        let mut stdout = Vec::new();
        let mut stderr = Vec::new();
        let mut stdin = || Ok(input.as_bytes().to_vec());
        let code = Cli {
            stdout: &mut stdout,
            stderr: &mut stderr,
            stdin: &mut stdin,
        }
        .run(&argv)
        .unwrap();
        TestRun {
            code,
            stdout: String::from_utf8(stdout).unwrap(),
            stderr: String::from_utf8(stderr).unwrap(),
        }
    }

    #[test]
    fn check_reports_the_first_error_with_a_caret() {
        let result = run(&["check", "-"], "[s]\nprefix \"!\"\n");
        assert_eq!(result.code, 1);
        assert_eq!(
            result.stderr,
            "<stdin>:2:8: expected ':' after key 'prefix', found '\"'\n    prefix \"!\"\n           ^\n",
        );
    }

    #[test]
    fn fmt_prints_canonical_text() {
        let result = run(&["fmt", "--indent=4", "-"], "[s]\nk: {a: 1}\n");
        assert_eq!(result.code, 0);
        assert_eq!(result.stdout, "[s]\nk: {\n    a: 1\n}\n");
    }

    #[test]
    fn get_and_to_json() {
        assert_eq!(
            run(&["get", "-r", "-", "s.name"], "[s]\nname: hello\n").stdout,
            "hello\n"
        );
        let missing = run(&["get", "-", "s.nope"], "[s]\nname: hello\n");
        assert_eq!(
            (missing.code, missing.stderr.as_str()),
            (1, "no such path: s.nope\n")
        );
        assert_eq!(
            run(&["to-json", "-"], "a: [1]\n").stdout,
            "{\n  \"a\": [\n    1\n  ]\n}\n"
        );
    }

    #[test]
    fn from_json_keeps_big_integers_and_key_order() {
        let result = run(
            &["from-json", "-"],
            "{\"z\": 1, \"s\": {\"id\": 123456789012345678901234}}",
        );
        assert_eq!(result.stdout, "z: 1\n\n[s]\nid: 123456789012345678901234\n");
    }

    #[test]
    fn usage_errors_exit_with_2() {
        assert_eq!(run(&[], "").code, 2);
        let bad = run(&["fmt", "--indent", "x", "-"], "");
        assert_eq!(bad.code, 2);
        assert_eq!(
            bad.stderr,
            "usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...\njpcl fmt: error: argument --indent: invalid non-negative int value: 'x'\n",
        );
        assert_eq!(
            run(&["get"], "").stderr,
            "usage: jpcl get [-r] file path\njpcl get: error: the following arguments are required: file, path\n",
        );
    }
}
