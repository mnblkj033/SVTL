//! 状态面板插件：在游戏画面左上角画一个面板，显示时间/金钱/场景。
use std::os::raw::{c_char, c_int, c_void};
use svtl::api::*;

static mut HOST: *mut SvtlHost = std::ptr::null_mut();

unsafe extern "C" fn on_load(host: *mut SvtlHost, _user: *mut *mut c_void) -> c_int {
    HOST = host;
    if let Some(log) = (*host).log {
        log(host, 1, b"info-plugin: loaded\0".as_ptr() as *const c_char);
    }
    if let Some(sub) = (*host).subscribe {
        sub(host, EV_GAME_TICK, on_tick, std::ptr::null_mut());
    }
    0
}

unsafe extern "C" fn on_tick(
    host: *mut SvtlHost,
    ev: SvtlEvent,
    _payload: *const SvtlEventPayload,
    _user: *mut c_void,
) {
    if ev != EV_GAME_TICK || host.is_null() {
        return;
    }
    let h = &*host;
    let (Some(rect), Some(text)) = (h.draw_rect, h.draw_text) else {
        return;
    };

    // 面板背景
    rect(host, 40.0, 200.0, 360.0, 190.0, 0xCC101418, 1, 20);

    let minutes = h.game_time.map(|f| f(host)).unwrap_or(0);
    let money = h.game_money.map(|f| f(host)).unwrap_or(0);
    let stamina = h.game_stamina.map(|f| f(host)).unwrap_or(0);
    let loc = h.player_location.map(|f| f(host)).unwrap_or(std::ptr::null());
    let location = if loc.is_null() {
        String::from("?")
    } else {
        std::ffi::CStr::from_ptr(loc).to_string_lossy().into_owned()
    };

    let hh = (minutes / 100).clamp(0, 23);
    let mm = (minutes % 100).clamp(0, 59);

    let lines = [
        ("SVTL 状态面板".to_string(), 0xFFFFE9B8u32, 24.0f32),
        (format!("时间  {:02}:{:02}", hh, mm), 0xFFE7EAEC, 20.0),
        (format!("金钱  {money}"), 0xFFE7EAEC, 20.0),
        (format!("体力  {stamina}"), 0xFFE7EAEC, 20.0),
        (format!("场景  {location}"), 0xFFA7B0B7, 18.0),
    ];
    let mut y = 232.0;
    for (s, color, size) in lines {
        let c = std::ffi::CString::new(s).unwrap();
        text(host, 60.0, y, c.as_ptr(), color, size, 21);
        y += 30.0;
    }
}

struct Holder(SvtlPlugin);
unsafe impl Sync for Holder {}

static PLUGIN: Holder = Holder(SvtlPlugin {
    abi_version: ABI_VERSION,
    id: b"com.svtl.info\0".as_ptr() as *const c_char,
    name: c"SVTL 状态面板".as_ptr() as *const c_char,
    version: b"0.1.0\0".as_ptr() as *const c_char,
    author: b"svtl\0".as_ptr() as *const c_char,
    game_version_range: b"1.6.*\0".as_ptr() as *const c_char,
    on_load: Some(on_load),
    on_unload: None,
    on_event: None,
    on_tick: None,
    on_render: None,
    on_tap: None,
});

#[no_mangle]
pub extern "C" fn svtl_plugin_entry() -> *const SvtlPlugin {
    &PLUGIN.0
}
