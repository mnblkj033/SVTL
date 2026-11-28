//! 宿主状态与 SvtlHost 函数表。
use crate::api::*;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::{Mutex, OnceLock};

#[derive(Default, Clone)]
pub struct GameState {
    pub time_minutes: i64,
    pub day: i32,
    pub season: i32,
    pub year: i32,
    pub money: i32,
    pub stamina: i32,
    pub max_stamina: i32,
    pub health: i32,
    pub weather: i32,
    pub name: String,
    pub location: String,
    pub position: String,
    pub multiplayer: bool,
    pub day_of_week: i32,
    pub farm_name: String,
    pub inventory_count: i32,
}

#[derive(Clone, Copy)]
pub struct Sub {
    pub ev: SvtlEvent,
    pub f: EventFn,
    pub user: *mut c_void,
}
unsafe impl Send for Sub {}

#[derive(Clone)]
pub struct DrawCmd {
    pub kind: u8, // 0 文本 1 矩形
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    pub text: String,
    pub color: u32,
    pub size: f32,
    pub layer: i32,
    pub filled: bool,
    pub path: String,
}

pub struct LoadedPlugin {
    pub id: String,
    pub name: String,
    pub version: String,
    pub handle: *mut c_void,
    pub user: *mut c_void,
    pub entry: *const SvtlPlugin,
}
unsafe impl Send for LoadedPlugin {}

pub struct Core {
    pub logs: Vec<String>,
    pub subs: Vec<Sub>,
    pub plugins: Vec<LoadedPlugin>,
    pub draws: Vec<DrawCmd>,
    pub config: HashMap<String, String>,
    pub data_root: String,
    pub capture: bool,
    pub block: bool,
    pub overlay: bool,
    pub touch_enabled: bool,
    pub mono_ready: bool,
    pub game: GameState,
}

static CORE: OnceLock<Mutex<Core>> = OnceLock::new();

pub fn core() -> &'static Mutex<Core> {
    CORE.get_or_init(|| {
        Mutex::new(Core {
            logs: Vec::new(),
            subs: Vec::new(),
            plugins: Vec::new(),
            draws: Vec::new(),
            config: HashMap::new(),
            data_root: String::new(),
            capture: false,
            block: false,
            overlay: false,
            touch_enabled: false,
            mono_ready: false,
            game: GameState::default(),
        })
    })
}

thread_local! {
    static CURRENT: std::cell::RefCell<Option<String>> = const { std::cell::RefCell::new(None) };
}

pub fn set_current(id: Option<String>) {
    CURRENT.with(|c| *c.borrow_mut() = id);
}

fn current() -> String {
    CURRENT.with(|c| c.borrow().clone().unwrap_or_default())
}

fn leak(s: &str) -> *const c_char {
    CString::new(s)
        .map(|c| c.into_raw() as *const c_char)
        .unwrap_or(std::ptr::null())
}

unsafe fn cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    CStr::from_ptr(p).to_string_lossy().into_owned()
}

#[cfg(target_os = "android")]
extern "C" {
    fn __android_log_write(prio: i32, tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) -> i32;
}

pub fn log_line(line: &str) {
    println!("{line}");
    // Android 上 println 看不到，额外写一份到 logcat
    #[cfg(target_os = "android")]
    unsafe {
        if let (Ok(tag), Ok(msg)) = (
            std::ffi::CString::new("SVTL"),
            std::ffi::CString::new(line.replace('\0', "")),
        ) {
            __android_log_write(4, tag.as_ptr(), msg.as_ptr());
        }
    }
    if let Ok(mut c) = core().lock() {
        c.logs.push(line.to_string());
        if c.logs.len() > 2000 {
            c.logs.remove(0);
        }
    }
}

/* ---------------- 宿主函数 ---------------- */

unsafe extern "C" fn h_log(_h: *mut SvtlHost, level: c_int, msg: *const c_char) {
    let tag = match level {
        0 => "D",
        2 => "W",
        3 => "E",
        _ => "I",
    };
    log_line(&format!("[{tag}] {}", cstr(msg)));
}

unsafe extern "C" fn h_subscribe(
    _h: *mut SvtlHost,
    ev: SvtlEvent,
    f: EventFn,
    user: *mut c_void,
) -> c_int {
    if !(0..EV_COUNT).contains(&ev) {
        return 1;
    }
    if let Ok(mut c) = core().lock() {
        c.subs.push(Sub { ev, f, user });
        return 0;
    }
    1
}

unsafe extern "C" fn h_unsubscribe(_h: *mut SvtlHost, ev: SvtlEvent, user: *mut c_void) {
    if let Ok(mut c) = core().lock() {
        c.subs.retain(|s| !(s.ev == ev && s.user == user));
    }
}

unsafe extern "C" fn h_hook(
    _h: *mut SvtlHost,
    ty: *const c_char,
    method: *const c_char,
    f: *mut c_void,
    _user: *mut c_void,
) -> u64 {
    crate::mono::request_hook(&cstr(ty), &cstr(method), f)
}

unsafe extern "C" fn h_unhook(_h: *mut SvtlHost, id: u64) {
    crate::mono::remove_hook(id);
}

macro_rules! get_i64 {
    ($name:ident, $field:ident) => {
        unsafe extern "C" fn $name(_h: *mut SvtlHost) -> i64 {
            core().lock().map(|c| c.game.$field as i64).unwrap_or(0)
        }
    };
}
macro_rules! get_i32 {
    ($name:ident, $field:ident) => {
        unsafe extern "C" fn $name(_h: *mut SvtlHost) -> c_int {
            core().lock().map(|c| c.game.$field).unwrap_or(0)
        }
    };
}

get_i64!(h_time, time_minutes);
get_i32!(h_day, day);
get_i32!(h_season, season);
get_i32!(h_year, year);
get_i32!(h_money, money);
get_i32!(h_stamina, stamina);
get_i32!(h_max_stamina, max_stamina);
get_i32!(h_health, health);
get_i32!(h_weather, weather);

unsafe extern "C" fn h_name(_h: *mut SvtlHost) -> *const c_char {
    leak(&core().lock().map(|c| c.game.name.clone()).unwrap_or_default())
}
unsafe extern "C" fn h_location(_h: *mut SvtlHost) -> *const c_char {
    leak(&core().lock().map(|c| c.game.location.clone()).unwrap_or_default())
}
unsafe extern "C" fn h_position(_h: *mut SvtlHost) -> *const c_char {
    leak(&core().lock().map(|c| c.game.position.clone()).unwrap_or_default())
}


unsafe extern "C" fn h_day_of_week(_h: *mut SvtlHost) -> c_int {
    core().lock().map(|c| c.game.day_of_week).unwrap_or(0)
}

unsafe extern "C" fn h_farm_name(_h: *mut SvtlHost) -> *const c_char {
    leak(&core().lock().map(|c| c.game.farm_name.clone()).unwrap_or_default())
}

unsafe extern "C" fn h_inventory_count(_h: *mut SvtlHost) -> c_int {
    core().lock().map(|c| c.game.inventory_count).unwrap_or(0)
}

unsafe extern "C" fn h_save_dir(_h: *mut SvtlHost) -> *const c_char {
    let root = core().lock().map(|c| c.data_root.clone()).unwrap_or_default();
    leak(&format!("{root}/saves"))
}


unsafe extern "C" fn h_send_packet(_h: *mut SvtlHost, data: *const c_void, len: usize) -> c_int {
    if data.is_null() {
        return 1;
    }
    let bytes = std::slice::from_raw_parts(data as *const u8, len);
    let hex: String = bytes.iter().take(32).map(|b| format!("{b:02x}")).collect();
    dispatch(EV_PACKET_OUT, &format!("{{\"len\":{len},\"hex\":\"{hex}\"}}"));
    0
}
unsafe extern "C" fn h_capture(_h: *mut SvtlHost, on: c_int) {
    if let Ok(mut c) = core().lock() {
        c.capture = on != 0;
    }
}
unsafe extern "C" fn h_block(_h: *mut SvtlHost, on: c_int) {
    if let Ok(mut c) = core().lock() {
        c.block = on != 0;
    }
}
unsafe extern "C" fn h_mp(_h: *mut SvtlHost) -> c_int {
    core().lock().map(|c| c.game.multiplayer as c_int).unwrap_or(0)
}

unsafe extern "C" fn h_overlay(_h: *mut SvtlHost, on: c_int) {
    if let Ok(mut c) = core().lock() {
        c.overlay = on != 0;
    }
}

unsafe extern "C" fn h_draw_text(
    _h: *mut SvtlHost,
    x: f32,
    y: f32,
    text: *const c_char,
    color: u32,
    size: f32,
    layer: c_int,
) {
    let s = cstr(text);
    if let Ok(mut c) = core().lock() {
        c.draws.push(DrawCmd {
            kind: 0,
            x,
            y,
            w: 0.0,
            h: 0.0,
            text: s,
            color,
            size,
            layer,
            filled: true,
            path: String::new(),
        });
    }
}

unsafe extern "C" fn h_draw_rect(
    _h: *mut SvtlHost,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    color: u32,
    filled: c_int,
    layer: c_int,
) {
    if let Ok(mut c) = core().lock() {
        c.draws.push(DrawCmd {
            kind: 1,
            x,
            y,
            w,
            h,
            text: String::new(),
            color,
            size: 0.0,
            layer,
            filled: filled != 0,
            path: String::new(),
        });
    }
}

unsafe extern "C" fn h_draw_clear(_h: *mut SvtlHost, layer: c_int) {
    if let Ok(mut c) = core().lock() {
        c.draws.retain(|d| d.layer != layer);
    }
}

unsafe extern "C" fn h_cfg_get(_h: *mut SvtlHost, key: *const c_char) -> *const c_char {
    let k = format!("{}|{}", current(), cstr(key));
    let v = core()
        .lock()
        .ok()
        .and_then(|c| c.config.get(&k).cloned())
        .unwrap_or_default();
    leak(&v)
}

unsafe extern "C" fn h_cfg_set(
    _h: *mut SvtlHost,
    key: *const c_char,
    value: *const c_char,
) -> c_int {
    let k = format!("{}|{}", current(), cstr(key));
    if let Ok(mut c) = core().lock() {
        c.config.insert(k, cstr(value));
        return 0;
    }
    1
}

unsafe extern "C" fn h_data_dir(_h: *mut SvtlHost) -> *const c_char {
    let root = core().lock().map(|c| c.data_root.clone()).unwrap_or_default();
    leak(&format!("{root}/plugins/{}", current()))
}

unsafe extern "C" fn h_plugin_id(_h: *mut SvtlHost) -> *const c_char {
    leak(&current())
}

unsafe extern "C" fn h_draw_image(
    _h: *mut SvtlHost,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    path: *const c_char,
    layer: c_int,
) {
    let p = cstr(path);
    if let Ok(mut c) = core().lock() {
        c.draws.push(DrawCmd {
            kind: 2,
            x,
            y,
            w,
            h,
            text: String::new(),
            color: 0xFFFFFFFF,
            size: 0.0,
            layer,
            filled: true,
            path: p,
        });
    }
}

unsafe extern "C" fn h_ui_touch(_h: *mut SvtlHost, on: c_int) {
    if let Ok(mut c) = core().lock() {
        c.touch_enabled = on != 0;
    }
}

unsafe extern "C" fn h_version(_h: *mut SvtlHost) -> *const c_char {
    leak(env!("CARGO_PKG_VERSION"))
}

unsafe extern "C" fn h_query(_h: *mut SvtlHost, _n: *const c_char, _v: u32) -> *mut c_void {
    std::ptr::null_mut()
}

static HOST: OnceLock<SvtlHost> = OnceLock::new();

pub fn host() -> &'static SvtlHost {
    HOST.get_or_init(|| SvtlHost {
        abi_version: ABI_VERSION,
        size: std::mem::size_of::<SvtlHost>() as u32,
        log: Some(h_log),
        subscribe: Some(h_subscribe),
        unsubscribe: Some(h_unsubscribe),
        hook_managed: Some(h_hook),
        unhook: Some(h_unhook),
        game_time: Some(h_time),
        game_day: Some(h_day),
        game_season: Some(h_season),
        game_year: Some(h_year),
        game_money: Some(h_money),
        game_stamina: Some(h_stamina),
        game_max_stamina: Some(h_max_stamina),
        game_health: Some(h_health),
        game_weather: Some(h_weather),
        player_name: Some(h_name),
        player_location: Some(h_location),
        player_position: Some(h_position),
        game_day_of_week: Some(h_day_of_week),
        farm_name: Some(h_farm_name),
        inventory_count: Some(h_inventory_count),
        save_dir: Some(h_save_dir),
        send_packet: Some(h_send_packet),
        set_packet_capture: Some(h_capture),
        set_packet_block: Some(h_block),
        is_multiplayer: Some(h_mp),
        overlay_set: Some(h_overlay),
        draw_text: Some(h_draw_text),
        draw_rect: Some(h_draw_rect),
        draw_clear_layer: Some(h_draw_clear),
        config_get: Some(h_cfg_get),
        config_set: Some(h_cfg_set),
        data_dir: Some(h_data_dir),
        plugin_id: Some(h_plugin_id),
        draw_image: Some(h_draw_image),
        ui_touch: Some(h_ui_touch),
        host_version: Some(h_version),
        query_interface: Some(h_query),
    })
}

pub fn host_ptr() -> *mut SvtlHost {
    host() as *const SvtlHost as *mut SvtlHost
}

pub fn dispatch(ev: SvtlEvent, json: &str) {
    let subs: Vec<Sub> = match core().lock() {
        Ok(c) => c.subs.iter().copied().filter(|s| s.ev == ev).collect(),
        Err(_) => return,
    };
    if subs.is_empty() {
        return;
    }
    let cjson = CString::new(json).unwrap_or_default();
    let payload = SvtlEventPayload {
        json: cjson.as_ptr(),
        native: std::ptr::null_mut(),
    };
    for s in subs {
        unsafe { (s.f)(host_ptr(), ev, &payload, s.user) };
    }
}

pub fn touch_enabled() -> bool {
    core().lock().map(|c| c.touch_enabled).unwrap_or(false)
}

pub fn update_game(f: impl FnOnce(&mut GameState)) {
    if let Ok(mut c) = core().lock() {
        f(&mut c.game);
    }
}
