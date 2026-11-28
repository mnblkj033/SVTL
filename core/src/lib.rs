//! SVTL 核心（Rust）：注入游戏进程，负责 Mono 交互、钩子、事件、渲染指令、插件加载。
pub mod api;
pub mod game;
pub mod hook;
pub mod host;
pub mod jni;
pub mod mono;
pub mod net;
pub mod plugin;
pub mod probe;
pub mod render;
pub mod save;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};

fn read_str(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn leak(s: String) -> *const c_char {
    CString::new(s)
        .map(|c| c.into_raw() as *const c_char)
        .unwrap_or(std::ptr::null())
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
}

#[no_mangle]
pub extern "C" fn svtl_init(data_root: *const c_char) -> c_int {
    let root = read_str(data_root);
    if let Ok(mut c) = host::core().lock() {
        c.data_root = root.clone();
    }
    host::log_line(&format!("[core] 初始化，数据目录 {root}"));
    // 这里不碰 Mono：运行时还没起来，任何 mono_* 调用都可能崩。
    // 需要时由 svtl_mono_boot 显式初始化。
    host::dispatch(api::EV_GAME_LAUNCHED, "{}");
    0
}

#[no_mangle]
pub extern "C" fn svtl_mono_boot(asm_dir: *const c_char) -> c_int {
    mono::boot(&read_str(asm_dir)) as c_int
}

#[no_mangle]
pub extern "C" fn svtl_plugins_load(dir: *const c_char, game_version: *const c_char) -> c_int {
    let d = read_str(dir);
    let v = read_str(game_version);
    plugin::load_dir(&d, if v.is_empty() { "1.6.15.3" } else { &v }) as c_int
}

#[no_mangle]
pub extern "C" fn svtl_tick(dt: f64) {
    plugin::tick(dt);
    host::dispatch(api::EV_GAME_TICK, &format!("{{\"dt\":{dt}}}"));
}

#[no_mangle]
pub extern "C" fn svtl_dispatch(ev: api::SvtlEvent, json: *const c_char) {
    host::dispatch(ev, &read_str(json));
}

#[no_mangle]
pub extern "C" fn svtl_hook(type_name: *const c_char, method: *const c_char, replacement: *mut c_void) -> u64 {
    mono::request_hook(&read_str(type_name), &read_str(method), replacement)
}

#[no_mangle]
pub extern "C" fn svtl_read_save(path: *const c_char) -> c_int {
    save::read_and_dispatch(&read_str(path)) as c_int
}

#[no_mangle]
pub extern "C" fn svtl_install_hooks() -> c_int {
    game::install_default() as c_int
}

#[no_mangle]
pub extern "C" fn svtl_probe_start() -> c_int {
    probe::start();
    1
}

#[no_mangle]
pub extern "C" fn svtl_ui_tap(x: f32, y: f32) -> c_int {
    if !host::touch_enabled() {
        return 0;
    }
    plugin::tap(x, y);
    1
}

#[no_mangle]
pub extern "C" fn svtl_touch_enabled() -> c_int {
    host::touch_enabled() as c_int
}

#[no_mangle]
pub extern "C" fn svtl_plugins_reload(dir: *const c_char, game_version: *const c_char) -> c_int {
    let d = read_str(dir);
    let v = read_str(game_version);
    plugin::reload(&d, if v.is_empty() { "1.6.15.3" } else { &v }) as c_int
}

#[no_mangle]
pub extern "C" fn svtl_hook_count() -> c_int {
    mono::hook_count() as c_int
}

#[no_mangle]
pub extern "C" fn svtl_state_json() -> *const c_char {
    let g = host::core().lock().map(|c| c.game.clone()).unwrap_or_default();
    leak(format!(
        "{{\"time\":{},\"day\":{},\"season\":{},\"year\":{},\"money\":{},\"stamina\":{},\"health\":{},\"weather\":{},\"name\":\"{}\",\"location\":\"{}\",\"position\":\"{}\",\"multiplayer\":{}}}",
        g.time_minutes, g.day, g.season, g.year, g.money, g.stamina, g.health, g.weather,
        g.name, g.location, g.position, g.multiplayer
    ))
}

#[no_mangle]
pub extern "C" fn svtl_draw_json() -> *const c_char {
    let draws = host::core().lock().map(|c| c.draws.clone()).unwrap_or_default();
    let mut out = String::from("[");
    for (i, d) in draws.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        if d.kind == 0 {
            out.push_str(&format!(
                "{{\"t\":\"text\",\"x\":{:.1},\"y\":{:.1},\"s\":\"{}\",\"c\":{},\"z\":{},\"size\":{:.1}}}",
                d.x, d.y, escape(&d.text), d.color, d.layer, d.size
            ));
        } else if d.kind == 2 {
            out.push_str(&format!(
                "{{\"t\":\"img\",\"x\":{:.1},\"y\":{:.1},\"w\":{:.1},\"h\":{:.1},\"z\":{},\"p\":\"{}\"}}",
                d.x, d.y, d.w, d.h, d.layer, escape(&d.path)
            ));
        } else {
            out.push_str(&format!(
                "{{\"t\":\"rect\",\"x\":{:.1},\"y\":{:.1},\"w\":{:.1},\"h\":{:.1},\"c\":{},\"z\":{},\"f\":{}}}",
                d.x, d.y, d.w, d.h, d.color, d.layer, d.filled as u8
            ));
        }
    }
    out.push(']');
    leak(out)
}

#[no_mangle]
pub extern "C" fn svtl_draw_clear() {
    if let Ok(mut c) = host::core().lock() {
        c.draws.clear();
    }
}

#[no_mangle]
pub extern "C" fn svtl_logs_json() -> *const c_char {
    let logs = host::core().lock().map(|c| c.logs.clone()).unwrap_or_default();
    let body: Vec<String> = logs.iter().map(|l| format!("\"{}\"", escape(l))).collect();
    leak(format!("[{}]", body.join(",")))
}

#[no_mangle]
pub extern "C" fn svtl_update_state(json: *const c_char) -> c_int {
    let text = read_str(json);
    let mut n = 0;
    host::update_game(|g| {
        if let Some(v) = field(&text, "money") {
            g.money = v;
            n += 1;
        }
        if let Some(v) = field(&text, "stamina") {
            g.stamina = v;
            n += 1;
        }
        if let Some(v) = field(&text, "time") {
            g.time_minutes = v as i64;
            n += 1;
        }
        if let Some(v) = field(&text, "day") {
            g.day = v;
            n += 1;
        }
        if let Some(v) = field(&text, "season") {
            g.season = v;
            n += 1;
        }
        if let Some(s) = str_field(&text, "location") {
            g.location = s;
            n += 1;
        }
        if let Some(s) = str_field(&text, "name") {
            g.name = s;
            n += 1;
        }
    });
    n
}

fn field(text: &str, key: &str) -> Option<i32> {
    let needle = format!("\"{key}\"");
    let i = text.find(&needle)?;
    let rest = &text[i + needle.len()..];
    let colon = rest.find(':')?;
    let tail = rest[colon + 1..].trim_start();
    let end = tail
        .find(|c: char| !(c.is_ascii_digit() || c == '-'))
        .unwrap_or(tail.len());
    tail[..end].parse().ok()
}

fn str_field(text: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\"");
    let i = text.find(&needle)?;
    let rest = &text[i + needle.len()..];
    let colon = rest.find(':')?;
    let tail = rest[colon + 1..].trim_start().strip_prefix('"')?;
    Some(tail.chars().take_while(|c| *c != '"').collect())
}

/* ---------------- JNI（com.svtl.launcher.NativeCore） ---------------- */

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_init(
    env: *mut c_void,
    _cls: *mut c_void,
    path: *mut c_void,
) -> c_int {
    let root = jni::string_of(env, path).unwrap_or_default();
    let c = CString::new(root).unwrap_or_default();
    svtl_init(c.as_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_loadPlugins(
    env: *mut c_void,
    _cls: *mut c_void,
    dir: *mut c_void,
    version: *mut c_void,
) -> c_int {
    let d = jni::string_of(env, dir).unwrap_or_default();
    let v = jni::string_of(env, version).unwrap_or_default();
    let cd = CString::new(d).unwrap_or_default();
    let cv = CString::new(v).unwrap_or_default();
    svtl_plugins_load(cd.as_ptr(), cv.as_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_tick(
    _env: *mut c_void,
    _cls: *mut c_void,
    dt: f64,
) {
    svtl_tick(dt);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_stateJson(
    env: *mut c_void,
    _cls: *mut c_void,
) -> *mut c_void {
    let s = CStr::from_ptr(svtl_state_json()).to_string_lossy().into_owned();
    jni::new_string(env, &s)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_drawClear(
    _env: *mut c_void,
    _cls: *mut c_void,
) {
    svtl_draw_clear();
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_drawJson(
    env: *mut c_void,
    _cls: *mut c_void,
) -> *mut c_void {
    let s = CStr::from_ptr(svtl_draw_json()).to_string_lossy().into_owned();
    jni::new_string(env, &s)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_logsJson(
    env: *mut c_void,
    _cls: *mut c_void,
) -> *mut c_void {
    let s = CStr::from_ptr(svtl_logs_json()).to_string_lossy().into_owned();
    jni::new_string(env, &s)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_readSave(
    env: *mut c_void,
    _cls: *mut c_void,
    path: *mut c_void,
) -> c_int {
    let p = jni::string_of(env, path).unwrap_or_default();
    let c = CString::new(p).unwrap_or_default();
    svtl_read_save(c.as_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_monoBoot(
    env: *mut c_void,
    _cls: *mut c_void,
    path: *mut c_void,
) -> c_int {
    let p = jni::string_of(env, path).unwrap_or_default();
    let c = CString::new(p).unwrap_or_default();
    svtl_mono_boot(c.as_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_installHooks(
    _env: *mut c_void,
    _cls: *mut c_void,
) -> c_int {
    svtl_install_hooks()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_probeStart(
    _env: *mut c_void,
    _cls: *mut c_void,
) -> c_int {
    svtl_probe_start()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_uiTap(
    _env: *mut c_void,
    _cls: *mut c_void,
    x: f32,
    y: f32,
) -> c_int {
    svtl_ui_tap(x, y)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_touchEnabled(
    _env: *mut c_void,
    _cls: *mut c_void,
) -> c_int {
    svtl_touch_enabled()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_reloadPlugins(
    env: *mut c_void,
    _cls: *mut c_void,
    dir: *mut c_void,
    version: *mut c_void,
) -> c_int {
    let d = jni::string_of(env, dir).unwrap_or_default();
    let v = jni::string_of(env, version).unwrap_or_default();
    let cd = CString::new(d).unwrap_or_default();
    let cv = CString::new(v).unwrap_or_default();
    svtl_plugins_reload(cd.as_ptr(), cv.as_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_svtl_launcher_NativeCore_hookCount(
    _env: *mut c_void,
    _cls: *mut c_void,
) -> c_int {
    svtl_hook_count()
}
