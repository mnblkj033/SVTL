//! 游戏状态轮询：不挂钩子，定时读游戏对象，变化了就发事件。
//!
//! 借用游戏自己初始化好的 Mono 运行时（合并包里它们同进程），
//! 所以不需要我们初始化运行时，也不会因为改指令崩游戏。
use crate::api::*;
use crate::host::{dispatch, log_line, update_game};
use crate::mono;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, Ordering};

static STARTED: AtomicBool = AtomicBool::new(false);

#[derive(Default, Clone, PartialEq)]
struct Snapshot {
    time: i32,
    money: i32,
    location: String,
    season: i32,
    weather: i32,
}

struct Handles {
    game1: *mut c_void,
    player_cls: *mut c_void,
    loc_cls: *mut c_void,
}
unsafe impl Send for Handles {}

/// 起后台轮询线程（只会起一次）。
pub fn start() {
    if STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| {
        log_line("[probe] 等待游戏运行时…");
        // 游戏启动后才会加载 libmonosgen-2.0.so，所以这里反复尝试打开
        for _ in 0..600 {
            if mono::try_resolve() && mono::root_ready() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        mono::ensure_thread();
        // 给游戏一点时间把运行时和程序集准备好，太早调用 mono 会崩
        std::thread::sleep(std::time::Duration::from_secs(5));
        let image = match mono::wait_loaded_image(180_000) {
            Some(i) => i,
            None => {
                log_line("[probe] 3 分钟内没等到游戏程序集，放弃");
                return;
            }
        };
        let game1 = match mono::class_in(image, "StardewValley", "Game1") {
            Some(c) => c,
            None => {
                log_line("[probe] 找不到 Game1");
                return;
            }
        };
        let player_cls = mono::class_in(image, "StardewValley", "Farmer");
        let loc_cls = mono::class_in(image, "StardewValley", "GameLocation");
        log_line("[probe] 已接上游戏运行时，开始采样");
        log_line(&format!(
            "[probe] 成员：timeOfDay {} · season {} · player {} · Money {} · location {} · weather {}",
            mono::has_field(game1, "timeOfDay"),
            mono::has_field(game1, "season"),
            mono::has_method(game1, "get_player"),
            player_cls.map(|c| mono::has_method(c, "get_Money")).unwrap_or(false),
            loc_cls.map(|c| mono::has_method(c, "get_Name")).unwrap_or(false),
            loc_cls.map(|c| mono::has_method(c, "IsRainingHere")).unwrap_or(false),
        ));

        let handles = Handles {
            game1,
            player_cls: player_cls.unwrap_or(std::ptr::null_mut()),
            loc_cls: loc_cls.unwrap_or(std::ptr::null_mut()),
        };
        let mut last = Snapshot::default();

        loop {
            mono::ensure_thread();
            if let Some(now) = sample(&handles) {
                if now != last {
                    emit(&last, &now);
                    last = now;
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(250));
        }
    });
}

fn sample(h: &Handles) -> Option<Snapshot> {
    // timeOfDay / season 是静态字段（int / Season 枚举，都是 32 位）
    let time = mono::static_int(h.game1, "timeOfDay").unwrap_or(0);
    let season = mono::static_int(h.game1, "season").unwrap_or(-1);

    // player / currentLocation 是静态**属性**，不是字段
    let player = mono::prop_object(h.game1, "player", std::ptr::null_mut());
    let loc = mono::prop_object(h.game1, "currentLocation", std::ptr::null_mut());

    let mut money = 0;
    if let (Some(p), false) = (player, h.player_cls.is_null()) {
        money = mono::prop_int(h.player_cls, "Money", p).unwrap_or(0);
    }
    let mut location = String::new();
    if let (Some(l), false) = (loc, h.loc_cls.is_null()) {
        location = mono::prop_string(h.loc_cls, "Name", l).unwrap_or_default();
    }
    let weather = match (loc, h.loc_cls.is_null()) {
        (Some(l), false) => weather_of(l, h.loc_cls),
        _ => -1,
    };
    Some(Snapshot { time, money, location, season, weather })
}

/// 天气在 1.6 里是每个地点单独的，用 GameLocation 的 Is*Here() 拼出旧版编码。
fn weather_of(loc: *mut c_void, loc_cls: *mut c_void) -> i32 {
    let yes = |m: &str| mono::call_bool(loc_cls, m, loc).unwrap_or(false);
    if yes("IsGreenRainingHere") {
        return 7; // 绿雨
    }
    if yes("IsLightningHere") {
        return 3;
    }
    if yes("IsSnowingHere") {
        return 5;
    }
    if yes("IsDebrisWeatherHere") {
        return 2;
    }
    if yes("IsRainingHere") {
        return 1;
    }
    0
}

fn emit(old: &Snapshot, new: &Snapshot) {
    update_game(|g| {
        g.time_minutes = new.time as i64;
        g.money = new.money;
        g.location = new.location.clone();
        g.season = new.season;
        g.weather = new.weather;
    });

    if old.money != new.money {
        dispatch(
            EV_MONEY_CHANGED,
            &format!("{{\"from\":{},\"to\":{}}}", old.money, new.money),
        );
    }
    if old.location != new.location && !new.location.is_empty() {
        dispatch(
            EV_PLAYER_WARPED,
            &format!(
                "{{\"from\":\"{}\",\"to\":\"{}\"}}",
                old.location, new.location
            ),
        );
    }
    if old.season != new.season && new.season >= 0 {
        dispatch(EV_SEASON_CHANGED, &format!("{{\"season\":{}}}", new.season));
    }
    if old.weather != new.weather && new.weather >= 0 {
        dispatch(EV_WEATHER_CHANGED, &format!("{{\"weather\":{}}}", new.weather));
    }
    if old.time != new.time {
        dispatch(EV_GAME_TICK, &format!("{{\"time\":{}}}", new.time));
    }
}
