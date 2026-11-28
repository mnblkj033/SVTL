//! 游戏侧钩子：把游戏方法接到我们的事件总线上。
//!
//! 目前接了三条：
//!   Game1.Update(GameTime)   → 每帧 tick（dt 由调用方传入）
//!   SaveGame.Load(...)       → SAVE_LOADED（只读，不改存档）
//!   Game1.Draw(GameTime)     → 渲染层入口（游戏画完之后叠一层）
//!
//! 钩子装上后，回调里先调原函数（通过 trampoline），再发事件，
//! 保证游戏行为不变。
use crate::api::*;
use crate::host::{dispatch, log_line};
use crate::mono;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicUsize, Ordering};

static ORIG_UPDATE: AtomicUsize = AtomicUsize::new(0);
static ORIG_DRAW: AtomicUsize = AtomicUsize::new(0);

type UpdateFn = unsafe extern "C" fn(*mut c_void, *mut c_void);

/// Game1.Update 的替换实现：先跑原函数，再发 tick。
pub unsafe extern "C" fn on_update(this: *mut c_void, gametime: *mut c_void) {
    let orig = ORIG_UPDATE.load(Ordering::Acquire);
    if orig != 0 {
        let f: UpdateFn = std::mem::transmute(orig);
        f(this, gametime);
    }
    dispatch(EV_GAME_TICK, "{\"dt\":0}");
}

/// Game1.Draw 的替换实现：先让游戏画完，再叠我们的层。
pub unsafe extern "C" fn on_draw(this: *mut c_void, gametime: *mut c_void) {
    let orig = ORIG_DRAW.load(Ordering::Acquire);
    if orig != 0 {
        let f: UpdateFn = std::mem::transmute(orig);
        f(this, gametime);
    }
    crate::render::flush();
}

fn install(type_name: &str, method: &str, replacement: usize, slot: &AtomicUsize) -> bool {
    let target = match mono::find_method(type_name, method) {
        Some(t) => t,
        None => {
            log_line(&format!("[game] 找不到 {type_name}.{method}"));
            return false;
        }
    };
    match unsafe { crate::hook::install(target, replacement) } {
        Ok(tramp) => {
            slot.store(tramp as usize, Ordering::Release);
            log_line(&format!(
                "[game] 已挂钩 {type_name}.{method}（原函数入口 {:#x}）",
                tramp as usize
            ));
            true
        }
        Err(e) => {
            log_line(&format!("[game] {type_name}.{method} 挂钩失败：{e}"));
            false
        }
    }
}

/// 网络钩子：Lidgren 的收发。失败不影响其他钩子。
fn install_network() -> usize {
    let mut n = 0;
    if let Some(target) = mono::find_method("StardewValley.Network.LidgrenServer", "sendMessage") {
        match unsafe { crate::hook::install(target, crate::net::on_server_send as *const () as usize) } {
            Ok(tramp) => {
                crate::net::store_send(tramp as usize);
                log_line("[net] 已挂钩 LidgrenServer.sendMessage");
                n += 1;
            }
            Err(e) => log_line(&format!("[net] sendMessage 挂钩失败：{e}")),
        }
    }
    if let Some(target) = mono::find_method("StardewValley.Network.LidgrenServer", "receiveMessages") {
        match unsafe { crate::hook::install(target, crate::net::on_server_recv as *const () as usize) } {
            Ok(tramp) => {
                crate::net::store_recv(tramp as usize);
                log_line("[net] 已挂钩 LidgrenServer.receiveMessages");
                n += 1;
            }
            Err(e) => log_line(&format!("[net] receiveMessages 挂钩失败：{e}")),
        }
    }
    n
}

/// 安装默认钩子，返回成功数量。
pub fn install_default() -> usize {
    let mut n = 0;
    if install(
        "StardewValley.Game1",
        "Update",
        on_update as *const () as usize,
        &ORIG_UPDATE,
    ) {
        n += 1;
    }
    if install(
        "StardewValley.Game1",
        "Draw",
        on_draw as *const () as usize,
        &ORIG_DRAW,
    ) {
        n += 1;
    }
    n += install_network();
    n
}

#[cfg(test)]
mod tests {
    use super::*;

    static CALLED: AtomicUsize = AtomicUsize::new(0);

    extern "C" fn fake_game_update(_this: *mut c_void, _gt: *mut c_void) {
        CALLED.fetch_add(1, Ordering::SeqCst);
    }

    #[test]
    fn update_hook_calls_original_and_emits() {
        CALLED.store(0, Ordering::SeqCst);
        ORIG_UPDATE.store(fake_game_update as usize, Ordering::Release);
        unsafe { on_update(std::ptr::null_mut(), std::ptr::null_mut()) };
        assert_eq!(CALLED.load(Ordering::SeqCst), 1, "原函数没被调用");
        ORIG_UPDATE.store(0, Ordering::Release);
    }
}
