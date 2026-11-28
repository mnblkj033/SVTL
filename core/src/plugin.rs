//! 插件加载：读 manifest.json → dlopen → 校验 ABI → on_load。
use crate::api::*;
use crate::host::{core, host_ptr, log_line, set_current, LoadedPlugin};
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::path::{Path, PathBuf};

extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlerror() -> *mut c_char;
}

const RTLD_NOW: c_int = 2;

#[derive(Default, Clone)]
pub struct Manifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub entry: String,
    pub game_version: String,
    pub enabled: bool,
}

fn json_field(text: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\"");
    let mut from = 0;
    while let Some(rel) = text[from..].find(&needle) {
        let start = from + rel;
        let rest = &text[start + needle.len()..];
        let colon = rest.find(':')?;
        let value = rest[colon + 1..].trim_start();
        if let Some(stripped) = value.strip_prefix('"') {
            let mut out = String::new();
            let mut esc = false;
            for ch in stripped.chars() {
                if esc {
                    out.push(ch);
                    esc = false;
                } else if ch == '\\' {
                    esc = true;
                } else if ch == '"' {
                    return Some(out);
                } else {
                    out.push(ch);
                }
            }
        } else if value.starts_with("true") {
            return Some("true".into());
        } else if value.starts_with("false") {
            return Some("false".into());
        }
        from = start + needle.len();
    }
    None
}

pub fn parse_manifest(text: &str) -> Manifest {
    Manifest {
        id: json_field(text, "id").unwrap_or_default(),
        name: json_field(text, "name").unwrap_or_default(),
        version: json_field(text, "version").unwrap_or_default(),
        entry: json_field(text, "entry").unwrap_or_default(),
        game_version: json_field(text, "gameVersion").unwrap_or_else(|| "*".into()),
        enabled: json_field(text, "enabled").map(|v| v == "true").unwrap_or(true),
    }
}

fn version_ok(range: &str, game: &str) -> bool {
    if range == "*" || range.is_empty() {
        return true;
    }
    match range.strip_suffix('*') {
        Some(prefix) => game.starts_with(prefix),
        None => game == range,
    }
}

pub fn load_dir(dir: &str, game_version: &str) -> usize {
    let root = Path::new(dir);
    if !root.is_dir() {
        log_line(&format!("[plugin] 目录不存在: {dir}"));
        return 0;
    }
    let mut entries: Vec<PathBuf> = std::fs::read_dir(root)
        .map(|rd| rd.filter_map(|e| e.ok().map(|e| e.path())).collect())
        .unwrap_or_default();
    entries.sort();
    let mut loaded = 0;
    for path in entries {
        if !path.is_dir() {
            continue;
        }
        let text = match std::fs::read_to_string(path.join("manifest.json")) {
            Ok(t) => t,
            Err(_) => continue,
        };
        let m = parse_manifest(&text);
        if m.id.is_empty() || m.entry.is_empty() {
            log_line(&format!("[plugin] 清单缺字段: {}", path.display()));
            continue;
        }
        if !m.enabled {
            log_line(&format!("[plugin] 已禁用: {}", m.id));
            continue;
        }
        if !version_ok(&m.game_version, game_version) {
            log_line(&format!(
                "[plugin] {} 要求游戏 {}，当前 {}",
                m.id, m.game_version, game_version
            ));
            continue;
        }
        if load_one(&path, &m) {
            loaded += 1;
        }
    }
    loaded
}

fn load_one(dir: &Path, m: &Manifest) -> bool {
    let so = dir.join(&m.entry);
    let cpath = match CString::new(so.to_string_lossy().as_bytes()) {
        Ok(c) => c,
        Err(_) => return false,
    };
    let handle = unsafe { dlopen(cpath.as_ptr(), RTLD_NOW) };
    if handle.is_null() {
        let err = unsafe {
            let e = dlerror();
            if e.is_null() {
                String::new()
            } else {
                CStr::from_ptr(e).to_string_lossy().into_owned()
            }
        };
        log_line(&format!("[plugin] {} dlopen 失败: {err}", m.id));
        return false;
    }
    let sym = unsafe { dlsym(handle, b"svtl_plugin_entry\0".as_ptr() as *const c_char) };
    if sym.is_null() {
        log_line(&format!("[plugin] {} 缺少 svtl_plugin_entry", m.id));
        return false;
    }
    let entry: PluginEntry = unsafe { std::mem::transmute(sym) };
    let plugin = unsafe { entry() };
    if plugin.is_null() {
        log_line(&format!("[plugin] {} 入口返回空", m.id));
        return false;
    }
    let abi = unsafe { (*plugin).abi_version };
    if abi != ABI_VERSION {
        log_line(&format!("[plugin] {} ABI {abi} != {ABI_VERSION}", m.id));
        return false;
    }
    set_current(Some(m.id.clone()));
    let mut user: *mut c_void = std::ptr::null_mut();
    let rc = match unsafe { (*plugin).on_load } {
        Some(f) => unsafe { f(host_ptr(), &mut user) },
        None => 0,
    };
    set_current(None);
    if rc != 0 {
        log_line(&format!("[plugin] {} on_load 返回 {rc}", m.id));
        return false;
    }
    log_line(&format!("[plugin] 已加载 {} v{}", m.name, m.version));
    if let Ok(mut c) = core().lock() {
        c.plugins.push(LoadedPlugin {
            id: m.id.clone(),
            name: m.name.clone(),
            version: m.version.clone(),
            handle,
            user,
            entry: plugin,
        });
    }
    true
}

pub fn unload_all() {
    let plugins: Vec<LoadedPlugin> = match core().lock() {
        Ok(mut c) => std::mem::take(&mut c.plugins),
        Err(_) => return,
    };
    for p in plugins {
        set_current(Some(p.id.clone()));
        if let Some(f) = unsafe { (*p.entry).on_unload } {
            unsafe { f(p.user) };
        }
        set_current(None);
    }
}

pub fn tick(dt: f64) {
    let entries: Vec<(String, *const SvtlPlugin, *mut c_void)> = match core().lock() {
        Ok(c) => c
            .plugins
            .iter()
            .map(|p| (p.id.clone(), p.entry, p.user))
            .collect(),
        Err(_) => return,
    };
    for (id, entry, user) in entries {
        set_current(Some(id.clone()));
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if let Some(f) = unsafe { (*entry).on_tick } {
                unsafe { f(user, dt) };
            }
        }));
        if r.is_err() {
            log_line(&format!("[plugin] {id} on_tick 抛异常"));
        }
        set_current(None);
    }
}

/// 把点击坐标分发给插件的 on_tap。
pub fn tap(x: f32, y: f32) {
    let entries: Vec<(String, *const SvtlPlugin, *mut c_void)> = match core().lock() {
        Ok(c) => c.plugins.iter().map(|p| (p.id.clone(), p.entry, p.user)).collect(),
        Err(_) => return,
    };
    for (id, entry, user) in entries {
        set_current(Some(id.clone()));
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if let Some(f) = unsafe { (*entry).on_tap } {
                unsafe { f(user, x, y) };
            }
        }));
        if r.is_err() {
            log_line(&format!("[plugin] {id} on_tap 抛异常，已禁用"));
        }
        set_current(None);
    }
}

/// 热重载：卸载全部插件后重新扫描目录。
pub fn reload(dir: &str, game_version: &str) -> usize {
    unload_all();
    log_line("[plugin] 重新加载插件…");
    load_dir(dir, game_version)
}
