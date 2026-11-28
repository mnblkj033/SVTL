//! 与 `cpp/include/svtl_plugin_api.h` 一一对应的 FFI 定义。
use std::os::raw::{c_char, c_int, c_void};

pub const ABI_VERSION: u32 = 1;

pub type SvtlEvent = i32;

pub const EV_GAME_LAUNCHED: SvtlEvent = 0;
pub const EV_GAME_TICK: SvtlEvent = 1;
pub const EV_DAY_STARTED: SvtlEvent = 2;
pub const EV_DAY_ENDING: SvtlEvent = 3;
pub const EV_SAVE_SAVING: SvtlEvent = 4;
pub const EV_SAVE_SAVED: SvtlEvent = 5;
pub const EV_SAVE_LOADED: SvtlEvent = 6;
pub const EV_PLAYER_WARPED: SvtlEvent = 7;
pub const EV_PLAYER_ACTION: SvtlEvent = 8;
pub const EV_ITEM_ADDED: SvtlEvent = 9;
pub const EV_INVENTORY_CHANGED: SvtlEvent = 10;
pub const EV_MONEY_CHANGED: SvtlEvent = 11;
pub const EV_DIALOGUE: SvtlEvent = 12;
pub const EV_MENU_OPENED: SvtlEvent = 13;
pub const EV_MENU_CLOSED: SvtlEvent = 14;
pub const EV_SCRIPT_EVENT_STARTED: SvtlEvent = 15;
pub const EV_SCRIPT_EVENT_ENDED: SvtlEvent = 16;
pub const EV_FESTIVAL_STARTED: SvtlEvent = 17;
pub const EV_SEASON_CHANGED: SvtlEvent = 18;
pub const EV_WEATHER_CHANGED: SvtlEvent = 19;
pub const EV_QUEST_COMPLETED: SvtlEvent = 20;
pub const EV_CROP_HARVESTED: SvtlEvent = 21;
pub const EV_TOOL_USED: SvtlEvent = 22;
pub const EV_SHOP_OPENED: SvtlEvent = 23;
pub const EV_MAIL_RECEIVED: SvtlEvent = 24;
pub const EV_PLAYER_DIED: SvtlEvent = 25;
pub const EV_FESTIVAL_DAY: SvtlEvent = 26;
pub const EV_PACKET_IN: SvtlEvent = 27;
pub const EV_PACKET_OUT: SvtlEvent = 28;
pub const EV_PLAYER_JOINED: SvtlEvent = 29;
pub const EV_PLAYER_LEFT: SvtlEvent = 30;
pub const EV_COUNT: SvtlEvent = 31;

pub fn event_name(ev: SvtlEvent) -> &'static str {
    match ev {
        EV_GAME_LAUNCHED => "game.launched",
        EV_GAME_TICK => "game.tick",
        EV_DAY_STARTED => "day.started",
        EV_DAY_ENDING => "day.ending",
        EV_SAVE_SAVING => "save.saving",
        EV_SAVE_SAVED => "save.saved",
        EV_SAVE_LOADED => "save.loaded",
        EV_PLAYER_WARPED => "player.warped",
        EV_PLAYER_ACTION => "player.action",
        EV_ITEM_ADDED => "item.added",
        EV_INVENTORY_CHANGED => "inventory.changed",
        EV_MONEY_CHANGED => "money.changed",
        EV_DIALOGUE => "dialogue",
        EV_MENU_OPENED => "menu.opened",
        EV_MENU_CLOSED => "menu.closed",
        EV_SCRIPT_EVENT_STARTED => "event.started",
        EV_SCRIPT_EVENT_ENDED => "event.ended",
        EV_FESTIVAL_STARTED => "festival.started",
        EV_SEASON_CHANGED => "season.changed",
        EV_WEATHER_CHANGED => "weather.changed",
        EV_QUEST_COMPLETED => "quest.completed",
        EV_CROP_HARVESTED => "crop.harvested",
        EV_TOOL_USED => "tool.used",
        EV_SHOP_OPENED => "shop.opened",
        EV_MAIL_RECEIVED => "mail.received",
        EV_PLAYER_DIED => "player.died",
        EV_FESTIVAL_DAY => "festival.day",
        EV_PACKET_IN => "packet.in",
        EV_PACKET_OUT => "packet.out",
        EV_PLAYER_JOINED => "player.joined",
        EV_PLAYER_LEFT => "player.left",
        _ => "unknown",
    }
}

#[repr(C)]
pub struct SvtlEventPayload {
    pub json: *const c_char,
    pub native: *mut c_void,
}

pub type EventFn =
    unsafe extern "C" fn(*mut SvtlHost, SvtlEvent, *const SvtlEventPayload, *mut c_void);

#[repr(C)]
pub struct SvtlHost {
    pub abi_version: u32,
    pub size: u32,
    pub log: Option<unsafe extern "C" fn(*mut SvtlHost, c_int, *const c_char)>,
    pub subscribe:
        Option<unsafe extern "C" fn(*mut SvtlHost, SvtlEvent, EventFn, *mut c_void) -> c_int>,
    pub unsubscribe: Option<unsafe extern "C" fn(*mut SvtlHost, SvtlEvent, *mut c_void)>,
    pub hook_managed:
        Option<unsafe extern "C" fn(*mut SvtlHost, *const c_char, *const c_char, *mut c_void, *mut c_void) -> u64>,
    pub unhook: Option<unsafe extern "C" fn(*mut SvtlHost, u64)>,
    pub game_time: Option<unsafe extern "C" fn(*mut SvtlHost) -> i64>,
    pub game_day: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_season: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_year: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_money: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_stamina: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_max_stamina: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_health: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub game_weather: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub player_name: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub player_location: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub player_position: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub game_day_of_week: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub farm_name: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub inventory_count: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub save_dir: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub send_packet: Option<unsafe extern "C" fn(*mut SvtlHost, *const c_void, usize) -> c_int>,
    pub set_packet_capture: Option<unsafe extern "C" fn(*mut SvtlHost, c_int)>,
    pub set_packet_block: Option<unsafe extern "C" fn(*mut SvtlHost, c_int)>,
    pub is_multiplayer: Option<unsafe extern "C" fn(*mut SvtlHost) -> c_int>,
    pub overlay_set: Option<unsafe extern "C" fn(*mut SvtlHost, c_int)>,
    pub draw_text:
        Option<unsafe extern "C" fn(*mut SvtlHost, f32, f32, *const c_char, u32, f32, c_int)>,
    pub draw_rect:
        Option<unsafe extern "C" fn(*mut SvtlHost, f32, f32, f32, f32, u32, c_int, c_int)>,
    pub draw_clear_layer: Option<unsafe extern "C" fn(*mut SvtlHost, c_int)>,
    pub config_get: Option<unsafe extern "C" fn(*mut SvtlHost, *const c_char) -> *const c_char>,
    pub config_set:
        Option<unsafe extern "C" fn(*mut SvtlHost, *const c_char, *const c_char) -> c_int>,
    pub data_dir: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub plugin_id: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub draw_image:
        Option<unsafe extern "C" fn(*mut SvtlHost, f32, f32, f32, f32, *const c_char, c_int)>,
    pub ui_touch: Option<unsafe extern "C" fn(*mut SvtlHost, c_int)>,
    pub host_version: Option<unsafe extern "C" fn(*mut SvtlHost) -> *const c_char>,
    pub query_interface: Option<unsafe extern "C" fn(*mut SvtlHost, *const c_char, u32) -> *mut c_void>,
}

#[repr(C)]
pub struct SvtlPlugin {
    pub abi_version: u32,
    pub id: *const c_char,
    pub name: *const c_char,
    pub version: *const c_char,
    pub author: *const c_char,
    pub game_version_range: *const c_char,
    pub on_load: Option<unsafe extern "C" fn(*mut SvtlHost, *mut *mut c_void) -> c_int>,
    pub on_unload: Option<unsafe extern "C" fn(*mut c_void)>,
    pub on_event: Option<unsafe extern "C" fn(*mut c_void, SvtlEvent, *const SvtlEventPayload)>,
    pub on_tick: Option<unsafe extern "C" fn(*mut c_void, f64)>,
    pub on_render: Option<unsafe extern "C" fn(*mut c_void, *mut SvtlHost)>,
    pub on_tap: Option<unsafe extern "C" fn(*mut c_void, f32, f32)>,
}

pub type PluginEntry = unsafe extern "C" fn() -> *const SvtlPlugin;
