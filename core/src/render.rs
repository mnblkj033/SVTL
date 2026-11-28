//! 渲染层：插件写进绘制列表，游戏画完之后由这里叠一层。
//!
//! 后端可替换：`MonoBackend` 真正调 XNA 的 SpriteBatch（进游戏帧），
//! 测试里用 `MockBackend` 校验顺序。
use crate::host::core;
use crate::host::DrawCmd;
use crate::mono;
use std::os::raw::c_void;
use std::sync::{Mutex, OnceLock};

pub trait Backend {
    fn text(&mut self, x: f32, y: f32, text: &str, color: u32, size: f32);
    fn rect(&mut self, x: f32, y: f32, w: f32, h: f32, color: u32, filled: bool);
}

pub fn flush_with(backend: &mut dyn Backend, draws: &[DrawCmd]) {
    let mut ordered: Vec<&DrawCmd> = draws.iter().collect();
    ordered.sort_by_key(|d| d.layer);
    for d in ordered {
        if d.kind == 0 {
            backend.text(d.x, d.y, &d.text, d.color, d.size);
        } else {
            backend.rect(d.x, d.y, d.w, d.h, d.color, d.filled);
        }
    }
}

pub fn flush() {
    let draws = match core().lock() {
        Ok(mut c) => std::mem::take(&mut c.draws),
        Err(_) => return,
    };
    if draws.is_empty() {
        return;
    }
    let mut backend = MonoBackend;
    flush_with(&mut backend, &draws);
}

/* ---------------- 游戏内后端 ---------------- */

struct GameRefs {
    sprite_batch: *mut c_void,
    font: *mut c_void,
    begin: *mut c_void,
    end: *mut c_void,
    draw_string: *mut c_void,
}
unsafe impl Send for GameRefs {}

static REFS: OnceLock<Mutex<Option<GameRefs>>> = OnceLock::new();
static WARNED: Mutex<bool> = Mutex::new(false);

fn refs_cell() -> &'static Mutex<Option<GameRefs>> {
    REFS.get_or_init(|| Mutex::new(None))
}

fn warn_once(msg: &str) {
    if let Ok(mut w) = WARNED.lock() {
        if *w {
            return;
        }
        *w = true;
    }
    crate::host::log_line(msg);
}

/// 解析游戏对象；失败时记录原因，之后不再重试。
fn resolve() -> bool {
    if let Ok(c) = refs_cell().lock() {
        if c.is_some() {
            return true;
        }
    }
    if !mono::is_ready() {
        warn_once("[render] Mono 未就绪，渲染层降级为日志");
        return false;
    }
    mono::ensure_thread();

    let sprite_batch = mono::static_field_object("StardewValley.Game1", "spriteBatch");
    let font = mono::static_field_object("StardewValley.Game1", "dialogueFont");
    let (Some(sb), Some(font)) = (sprite_batch.filter(|p| !p.is_null()), font.filter(|p| !p.is_null()))
    else {
        warn_once("[render] 找不到 Game1.spriteBatch / dialogueFont");
        return false;
    };
    let begin = mono::find_method_of("Microsoft.Xna.Framework.Graphics.SpriteBatch", "Begin", 0);
    let end = mono::find_method_of("Microsoft.Xna.Framework.Graphics.SpriteBatch", "End", 0);
    let draw_string = mono::find_method_of("Microsoft.Xna.Framework.Graphics.SpriteBatch", "DrawString", 4);
    let (Some(begin), Some(end), Some(draw_string)) = (begin, end, draw_string) else {
        warn_once("[render] 找不到 SpriteBatch.Begin/End/DrawString");
        return false;
    };
    crate::host::log_line("[render] 游戏对象解析完成，开始往游戏帧里画");
    if let Ok(mut c) = refs_cell().lock() {
        *c = Some(GameRefs {
            sprite_batch: sb,
            font,
            begin,
            end,
            draw_string,
        });
    }
    true
}

pub struct MonoBackend;

impl Backend for MonoBackend {
    fn text(&mut self, x: f32, y: f32, text: &str, color: u32, size: f32) {
        let _ = size;
        if !resolve() {
            return;
        }
        let refs = match refs_cell().lock() {
            Ok(c) => c,
            Err(_) => return,
        };
        let Some(r) = refs.as_ref() else { return };

        // Vector2 (8 字节) / Color (4 字节 RGBA)
        let mut vec2: [f32; 2] = [x, y];
        let mut rgba: [u8; 4] = [
            ((color >> 16) & 0xff) as u8,
            ((color >> 8) & 0xff) as u8,
            (color & 0xff) as u8,
            ((color >> 24) & 0xff) as u8,
        ];
        let mono_str = mono::new_string(text);
        if mono_str.is_null() {
            return;
        }
        let mut args: [*mut c_void; 4] = [
            r.font,
            mono_str,
            vec2.as_mut_ptr() as *mut c_void,
            rgba.as_mut_ptr() as *mut c_void,
        ];

        unsafe {
            mono::invoke(r.begin, r.sprite_batch, std::ptr::null_mut());
            mono::invoke(r.draw_string, r.sprite_batch, args.as_mut_ptr());
            mono::invoke(r.end, r.sprite_batch, std::ptr::null_mut());
        }
    }

    fn rect(&mut self, x: f32, y: f32, w: f32, h: f32, color: u32, filled: bool) {
        // 需要一张 1x1 白纹理，暂时只记录，等接入 Texture2D 再补
        crate::host::log_line(&format!(
            "[render] rect ({x:.0},{y:.0}) {w:.0}x{h:.0} color={color:#010x} filled={filled}"
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Mock(Vec<String>);

    impl Backend for Mock {
        fn text(&mut self, x: f32, y: f32, text: &str, _c: u32, _s: f32) {
            self.0.push(format!("text@{x:.0},{y:.0}:{text}"));
        }
        fn rect(&mut self, x: f32, y: f32, _w: f32, _h: f32, _c: u32, _f: bool) {
            self.0.push(format!("rect@{x:.0},{y:.0}"));
        }
    }

    fn cmd(layer: i32, kind: u8, text: &str) -> DrawCmd {
        DrawCmd {
            kind,
            x: 10.0,
            y: 20.0,
            w: 5.0,
            h: 5.0,
            text: text.to_string(),
            color: 0xFFFFFFFF,
            size: 14.0,
            layer,
            filled: true,
        }
    }

    #[test]
    fn draws_in_layer_order() {
        let draws = vec![cmd(10, 0, "top"), cmd(0, 1, ""), cmd(5, 0, "mid")];
        let mut mock = Mock(Vec::new());
        flush_with(&mut mock, &draws);
        assert_eq!(
            mock.0,
            vec![
                "rect@10,20".to_string(),
                "text@10,20:mid".to_string(),
                "text@10,20:top".to_string()
            ]
        );
    }
}
