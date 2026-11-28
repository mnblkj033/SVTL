//! 示例插件：订阅 tick，每帧写一行字（C ABI，可被任何语言写的插件替换）。
use std::os::raw::{c_char, c_int, c_void};
use svtl::api::*;

unsafe extern "C" fn on_load(host: *mut SvtlHost, _user: *mut *mut c_void) -> c_int {
    let h = &*host;
    HOST.with(|p| *p.borrow_mut() = host);
    if let Some(log) = h.log {
        log(host, 1, b"hello-plugin: loaded\0".as_ptr() as *const c_char);
    }
    if let Some(touch) = h.ui_touch {
        touch(host, 1);
    }
    if let Some(sub) = h.subscribe {
        sub(host, EV_GAME_TICK, on_tick_event, std::ptr::null_mut());
        sub(host, EV_SAVE_LOADED, on_save_event, std::ptr::null_mut());
    }
    0
}

unsafe extern "C" fn on_tick_event(
    host: *mut SvtlHost,
    ev: SvtlEvent,
    _payload: *const SvtlEventPayload,
    _user: *mut c_void,
) {
    if ev != EV_GAME_TICK || host.is_null() {
        return;
    }
    let draw = (*host).draw_text;
    let rect = (*host).draw_rect;
    if let Some(rect) = rect {
        // 半透明面板
        rect(host, 40.0, 60.0, 320.0, 120.0, 0xCC1B1A16, 1, 10);
    }
    if let Some(draw) = draw {
        let title = std::ffi::CString::new("Hello SVTL").unwrap();
        let sub = std::ffi::CString::new("插件 GUI 叠加层").unwrap();
        draw(host, 60.0, 100.0, title.as_ptr(), 0xFFFFE9B8, 26.0, 11);
        draw(host, 60.0, 140.0, sub.as_ptr(), 0xFFB8C4CC, 18.0, 11);
    }
}

unsafe extern "C" fn on_save_event(
    host: *mut SvtlHost,
    ev: SvtlEvent,
    payload: *const SvtlEventPayload,
    _user: *mut c_void,
) {
    if ev != EV_SAVE_LOADED || host.is_null() || payload.is_null() {
        return;
    }
    let json = (*payload).json;
    if json.is_null() {
        return;
    }
    let text = std::ffi::CStr::from_ptr(json).to_string_lossy();
    if let Some(log) = (*host).log {
        let msg = std::ffi::CString::new(format!("读到存档：{text}")).unwrap();
        log(host, 1, msg.as_ptr());
    }
}

unsafe extern "C" fn on_tap(user: *mut c_void, x: f32, y: f32) {
    let host = crate::HOST.with(|h| *h.borrow());
    if host.is_null() {
        return;
    }
    if let Some(log) = (*host).log {
        let msg = std::ffi::CString::new(format!("点击 ({x:.0}, {y:.0})")).unwrap();
        log(host, 1, msg.as_ptr());
    }
}

thread_local! {
    static HOST: std::cell::RefCell<*mut SvtlHost> = const { std::cell::RefCell::new(std::ptr::null_mut()) };
}

struct Holder(SvtlPlugin);
unsafe impl Sync for Holder {}

static PLUGIN: Holder = Holder(SvtlPlugin {
    abi_version: ABI_VERSION,
    id: b"com.svtl.hello\0".as_ptr() as *const c_char,
    name: b"Hello SVTL\0".as_ptr() as *const c_char,
    version: b"0.1.0\0".as_ptr() as *const c_char,
    author: b"svtl\0".as_ptr() as *const c_char,
    game_version_range: b"1.6.*\0".as_ptr() as *const c_char,
    on_load: Some(on_load),
    on_unload: None,
    on_event: None,
    on_tick: None,
    on_render: None,
    on_tap: Some(on_tap),
});

#[no_mangle]
pub extern "C" fn svtl_plugin_entry() -> *const SvtlPlugin {
    &PLUGIN.0
}
